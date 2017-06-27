package org.http4s
package server
package middleware

import cats._
import cats.implicits._
import org.http4s.Method.OPTIONS
import org.http4s.headers._
import org.log4s.getLogger

import scala.concurrent.duration._

/**
  * CORS middleware config options.
  * You can give an instance of this class to the CORS middleware,
  * to specify its behavoir
  */
final case class CORSConfig(
  anyOrigin: Boolean,
  allowCredentials: Boolean,
  maxAge: Long,
  anyMethod: Boolean = true,
  allowedOrigins: Option[Set[String]] = None,
  allowedMethods: Option[Set[String]] = None,
  allowedHeaders: Option[Set[String]] = Set("Content-Type", "*").some
)

object CORS {
  private[CORS] val logger = getLogger

  def DefaultCORSConfig = CORSConfig(
    anyOrigin = true,
    allowCredentials = true,
    maxAge = 1.day.toSeconds)

  /**
   * CORS middleware
   * This middleware provides clients with CORS information
   * based on information in CORS config.
   * Currently, you cannot make permissions depend on request details
   */
  def apply[F[_]](service: HttpService[F], config: CORSConfig = DefaultCORSConfig)
                 (implicit F: Applicative[F]): HttpService[F] =
    Service.lift { req =>
      // In the case of an options request we want to return a simple response with the correct Headers set.
      def createOptionsResponse(origin: Header, acrm: Header): Response[F] = corsHeaders(origin.value, acrm.value)(Response())

      def corsHeaders(origin: String, acrm: String)(resp: Response[F]): Response[F] =
        config.allowedHeaders.map(_.mkString("", ", ", "")).fold(resp) { hs =>
          resp.putHeaders(Header("Access-Control-Allow-Headers", hs))
        }.putHeaders(
          Header("Vary", "Origin,Access-Control-Request-Methods"),
          Header("Access-Control-Allow-Credentials", config.allowCredentials.toString),
          Header("Access-Control-Allow-Methods", config.allowedMethods.fold(acrm)(_.mkString("", ", ", ""))),
          Header("Access-Control-Allow-Origin", origin),
          Header("Access-Control-Max-Age", config.maxAge.toString)
        )

      def allowCORS(origin: Header, acrm: Header): Boolean = (config.anyOrigin, config.anyMethod, origin.value, acrm.value) match {
        case (true, true, _, _) => true
        case (true, false, _, acrm) => config.allowedMethods.exists(_.contains(acrm))
        case (false, true, origin, _) => config.allowedOrigins.exists(_.contains(origin))
        case (false, false, origin, acrm) =>
          (config.allowedMethods.map(_.contains(acrm)) |@|
            config.allowedOrigins.map(_.contains(origin))).map {
            _ && _
          }.getOrElse(false)
      }

      (req.method, req.headers.get(Origin), req.headers.get(`Access-Control-Request-Method`)) match {
        case (OPTIONS, Some(origin), Some(acrm)) if allowCORS(origin, acrm) =>
          logger.debug(s"Serving OPTIONS with CORS headers for $acrm ${req.uri}")
          F.pure(createOptionsResponse(origin, acrm))
        case (_, Some(origin), _) =>
          if (allowCORS(origin, Header("Access-Control-Request-Method", req.method.renderString))) {
            service(req).map {
              case resp: Response[F] =>
                logger.debug(s"Adding CORS headers to ${req.method} ${req.uri}")
                corsHeaders(origin.value, req.method.renderString)(resp)
              case Pass() =>
                Pass()
            }
          }
          else {
            logger.debug(s"CORS headers were denied for ${req.method} ${req.uri}")
            service(req)
          }
        case _ =>
          // This request is out of scope for CORS
          service(req)
      }
    }
}
