/*
 * Copyright 2016 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.asynchttpclient.netty.handler;

import com.typesafe.netty.HandlerPublisher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamedResponsePublisher extends HandlerPublisher<HttpResponseBodyPart> {

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  private final ChannelManager channelManager;
  private final NettyResponseFuture<?> future;
  private final Channel channel;
  private volatile boolean hasOutstandingRequest = false;
  private Throwable error;

  StreamedResponsePublisher(EventExecutor executor, ChannelManager channelManager, NettyResponseFuture<?> future, Channel channel) {
    super(executor, HttpResponseBodyPart.class);
    this.channelManager = channelManager;
    this.future = future;
    this.channel = channel;
  }

  @Override
  protected void cancelled() {
    logger.debug("Subscriber cancelled, ignoring the rest of the body");

    try {
      future.done();
    } catch (Exception t) {
      // Never propagate exception once we know we are done.
      logger.debug(t.getMessage(), t);
    }

    // The subscriber cancelled early - this channel is dead and should be closed.
    channelManager.closeChannel(channel);
  }

  @Override
  protected void requestDemand() {
    hasOutstandingRequest = true;
    super.requestDemand();
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    super.channelReadComplete(ctx);
    hasOutstandingRequest = false;
  }

  @Override
  public void subscribe(Subscriber<? super HttpResponseBodyPart> subscriber) {
    super.subscribe(new ErrorReplacingSubscriber(subscriber));
  }

  public boolean hasOutstandingRequest() {
    return hasOutstandingRequest;
  }

  NettyResponseFuture<?> future() {
    return future;
  }

  public void setError(Throwable t) {
    this.error = t;
  }

  private class ErrorReplacingSubscriber implements Subscriber<HttpResponseBodyPart> {

    private final Subscriber<? super HttpResponseBodyPart> subscriber;

    ErrorReplacingSubscriber(Subscriber<? super HttpResponseBodyPart> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
      subscriber.onSubscribe(s);
    }

    @Override
    public void onNext(HttpResponseBodyPart httpResponseBodyPart) {
      subscriber.onNext(httpResponseBodyPart);
    }

    @Override
    public void onError(Throwable t) {
      subscriber.onError(t);
    }

    @Override
    public void onComplete() {
      Throwable replacementError = error;
      if (replacementError == null) {
        subscriber.onComplete();
      } else {
        subscriber.onError(replacementError);
      }
    }
  }
}
