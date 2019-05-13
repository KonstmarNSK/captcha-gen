package com.kostya.captchagen.captchagennettyserver;

import com.kostya.captchagencore.impl.CaptchaGenerator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class InboundChannelHandler extends SimpleChannelInboundHandler<Object> {
    private HttpRequest request;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest req = this.request = (HttpRequest) msg;
            QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
            decoder.parameters().forEach((key, value) -> System.out.println("key: " + key + ", value = " + value));
        }

        if (msg instanceof LastHttpContent) {
            if(!writeResponse(ctx)){
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    private boolean writeResponse(ChannelHandlerContext ctx) {
        boolean isKeepAlive = HttpUtil.isKeepAlive(request);
        byte[] bytes = CaptchaGenerator.getCaptcha();

        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                HttpResponseStatus.OK,

                Unpooled.wrappedBuffer(bytes)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");

        if (isKeepAlive) {
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        ChannelFuture f = ctx.write(response);
        f.addListener((ChannelFutureListener) future -> {
            CaptchaGenerator.returnArray(bytes);

            if(future.isSuccess()){
                System.out.println("success");
            }else {
                System.out.println("no success: ");
                future.cause().printStackTrace();
            }
        });

        return isKeepAlive;
    }


}
