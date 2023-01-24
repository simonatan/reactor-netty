/*
 * Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.http.server.logging;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpUtil;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.server.HttpServerRequest;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

/**
 * {@link ChannelHandler} for access log of HTTP/1.1.
 *
 * @author Violeta Georgieva
 * @author limaoning
 */
final class AccessLogHandlerH1 extends BaseAccessLogHandler {

	AccessLogArgProviderH1 accessLogArgProvider;

	AccessLogHandlerH1(@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
		super(accessLog);
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse response) {
			final HttpResponseStatus status = response.status();

			if (status.equals(HttpResponseStatus.CONTINUE)) {
				return ctx.write(msg);
			}

			if (accessLogArgProvider == null) {
				accessLogArgProvider = new AccessLogArgProviderH1(ctx.channel().remoteAddress());
			}

			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops instanceof HttpServerRequest) {
				accessLogArgProvider.request((HttpServerRequest) ops);
			}

			final boolean chunked = HttpUtil.isTransferEncodingChunked(response);
			accessLogArgProvider.response(response)
					.chunked(chunked);
			if (!chunked) {
				accessLogArgProvider.contentLength(HttpUtil.getContentLength(response, -1));
			}
		}
		if (msg instanceof LastHttpContent<?> lastHttpContent) {
			accessLogArgProvider.increaseContentLength(lastHttpContent.payload().readableBytes());
			return ctx.write(msg)
			          .addListener(future -> {
			              if (future.isSuccess()) {
			                  AccessLog log = accessLog.apply(accessLogArgProvider);
			                  if (log != null) {
			                      log.log();
			                  }
			                  accessLogArgProvider.clear();
			              }
			          });
		}
		if (msg instanceof Buffer buffer) {
			accessLogArgProvider.increaseContentLength(buffer.readableBytes());
		}
		else if (msg instanceof HttpContent<?> httpContent) {
			accessLogArgProvider.increaseContentLength(httpContent.payload().readableBytes());
		}
		return ctx.write(msg);
	}
}
