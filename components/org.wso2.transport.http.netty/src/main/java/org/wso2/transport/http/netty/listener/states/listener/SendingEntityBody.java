/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.listener.states.listener;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpOutboundRespListener;
import org.wso2.transport.http.netty.internal.HandlerExecutor;
import org.wso2.transport.http.netty.internal.HttpTransportContextHolder;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.listener.states.MessageStateContext;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;

import static org.wso2.transport.http.netty.common.Constants.HTTP_HEAD_METHOD;
import static org.wso2.transport.http.netty.common.Constants
        .IDLE_TIMEOUT_TRIGGERED_WHILE_WRITING_OUTBOUND_RESPONSE_BODY;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_CLOSED_WHILE_WRITING_OUTBOUND_RESPONSE_BODY;
import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_TO_HOST_CONNECTION_CLOSED;
import static org.wso2.transport.http.netty.common.Util.createFullHttpResponse;
import static org.wso2.transport.http.netty.common.Util.setupContentLengthRequest;
import static org.wso2.transport.http.netty.listener.states.StateUtil.ILLEGAL_STATE_ERROR;

/**
 * State between start and end of outbound response entity body write
 */
public class SendingEntityBody implements ListenerState {

    private static Logger log = LoggerFactory.getLogger(SendingEntityBody.class);
    private final HandlerExecutor handlerExecutor;
    private final HttpResponseFuture outboundRespStatusFuture;
    private final MessageStateContext messageStateContext;
    private boolean headersWritten;
    private long contentLength = 0;
    private boolean headRequest;
    private List<HttpContent> contentList = new ArrayList<>();
    private HttpCarbonMessage inboundRequestMsg;
    private ChannelHandlerContext sourceContext;
    private SourceHandler sourceHandler;

    SendingEntityBody(MessageStateContext messageStateContext, HttpResponseFuture outboundRespStatusFuture,
                      boolean headersWritten) {
        this.messageStateContext = messageStateContext;
        this.outboundRespStatusFuture = outboundRespStatusFuture;
        this.headersWritten = headersWritten;
        this.handlerExecutor = HttpTransportContextHolder.getInstance().getHandlerExecutor();
    }

    @Override
    public void readInboundRequestHeaders(HttpCarbonMessage inboundRequestMsg, HttpRequest inboundRequestHeaders) {
        log.warn("readInboundRequestHeaders {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void readInboundRequestBody(Object inboundRequestEntityBody) throws ServerConnectorException {
        log.warn("readInboundRequestBody {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void writeOutboundResponseHeaders(HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        log.warn("writeOutboundResponseHeaders {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void writeOutboundResponseBody(HttpOutboundRespListener outboundRespListener,
                                          HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        headRequest = outboundRespListener.getRequestDataHolder().getHttpMethod().equalsIgnoreCase(HTTP_HEAD_METHOD);
        inboundRequestMsg = outboundRespListener.getInboundRequestMsg();
        sourceContext = outboundRespListener.getSourceContext();
        sourceHandler = outboundRespListener.getSourceHandler();

        ChannelFuture outboundChannelFuture;
        if (httpContent instanceof LastHttpContent) {
            if (headersWritten) {
                outboundChannelFuture = checkHeadRequestAndWriteOutboundResponseBody(httpContent);
            } else {
                contentLength += httpContent.content().readableBytes();
                setupContentLengthRequest(outboundResponseMsg, contentLength);
                outboundChannelFuture = writeOutboundResponseHeaderAndBody(outboundRespListener, outboundResponseMsg,
                                                                           (LastHttpContent) httpContent);
            }

            if (!outboundRespListener.isKeepAlive()) {
                outboundChannelFuture.addListener(ChannelFutureListener.CLOSE);
            }
            if (handlerExecutor != null) {
                handlerExecutor.executeAtSourceResponseSending(outboundResponseMsg);
            }
        } else {
            if (headersWritten) {
                if (headRequest) {
                    httpContent.release();
                    return;
                }
                outboundRespListener.getSourceContext().writeAndFlush(httpContent);
            } else {
                this.contentList.add(httpContent);
                contentLength += httpContent.content().readableBytes();
            }
        }
    }

    @Override
    public void handleAbruptChannelClosure(ServerConnectorFuture serverConnectorFuture) {
        // OutboundResponseStatusFuture will be notified asynchronously via OutboundResponseListener.
        log.error(REMOTE_CLIENT_CLOSED_WHILE_WRITING_OUTBOUND_RESPONSE_BODY);
    }

    @Override
    public ChannelFuture handleIdleTimeoutConnectionClosure(ServerConnectorFuture serverConnectorFuture,
                                                            ChannelHandlerContext ctx) {
        // OutboundResponseStatusFuture will be notified asynchronously via OutboundResponseListener.
        log.error(IDLE_TIMEOUT_TRIGGERED_WHILE_WRITING_OUTBOUND_RESPONSE_BODY);
        return null;
    }

    private ChannelFuture checkHeadRequestAndWriteOutboundResponseBody(HttpContent httpContent) {
        ChannelFuture outboundChannelFuture;
        if (headRequest) {
            httpContent.release();
            outboundChannelFuture = writeOutboundResponseBody(new DefaultLastHttpContent());
        } else {
            outboundChannelFuture = writeOutboundResponseBody(httpContent);
        }
        return outboundChannelFuture;
    }

    private ChannelFuture writeOutboundResponseHeaderAndBody(HttpOutboundRespListener outboundRespListener,
                                                             HttpCarbonMessage outboundResponseMsg,
                                                             LastHttpContent lastHttpContent) {
        CompositeByteBuf allContent = Unpooled.compositeBuffer();
        for (HttpContent cachedHttpContent : contentList) {
            allContent.addComponent(true, cachedHttpContent.content());
        }
        allContent.addComponent(true, lastHttpContent.content());

        if (headRequest) {
            allContent.release();
            allContent = Unpooled.compositeBuffer();
            allContent.addComponent(true, new DefaultLastHttpContent().content());
        }

        HttpResponse fullOutboundResponse = createFullHttpResponse(outboundResponseMsg,
                                                                   outboundRespListener.getRequestDataHolder()
                                                                           .getHttpVersion(),
                                                                   outboundRespListener.getServerName(),
                                                                   outboundRespListener.isKeepAlive(), allContent);

        ChannelFuture outboundChannelFuture = sourceContext.writeAndFlush(fullOutboundResponse);
        checkForResponseWriteStatus(inboundRequestMsg, outboundRespStatusFuture, outboundChannelFuture);
        return outboundChannelFuture;
    }

    private ChannelFuture writeOutboundResponseBody(HttpContent lastHttpContent) {
        ChannelFuture outboundChannelFuture = sourceContext.writeAndFlush(lastHttpContent);
        checkForResponseWriteStatus(inboundRequestMsg, outboundRespStatusFuture, outboundChannelFuture);
        return outboundChannelFuture;
    }

    private void checkForResponseWriteStatus(HttpCarbonMessage inboundRequestMsg,
                                             HttpResponseFuture outboundRespStatusFuture, ChannelFuture channelFuture) {
        channelFuture.addListener(writeOperationPromise -> {
            Throwable throwable = writeOperationPromise.cause();
            if (throwable != null) {
                if (throwable instanceof ClosedChannelException) {
                    throwable = new IOException(REMOTE_CLIENT_TO_HOST_CONNECTION_CLOSED);
                }
                outboundRespStatusFuture.notifyHttpListener(throwable);
            } else {
                outboundRespStatusFuture.notifyHttpListener(inboundRequestMsg);
            }
            messageStateContext.setListenerState(
                    new ResponseCompleted(sourceHandler, messageStateContext, inboundRequestMsg));
            resetOutboundListenerState();
        });
    }

    private void resetOutboundListenerState() {
        contentList.clear();
        contentLength = 0;
        headersWritten = false;
    }
}