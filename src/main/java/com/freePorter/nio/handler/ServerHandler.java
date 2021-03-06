package com.freePorter.nio.handler;

import com.alibaba.fastjson.JSON;
import com.freePorter.nio.constant.ChatType;
import com.freePorter.nio.entity.Message;
import com.freePorter.nio.entity.MessageHeader;
import com.freePorter.nio.server.ChannelManager;
import com.freePorter.nio.utils.PropertyUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * @author zoush(zoush@hadlinks.com)
 *
 * CreateTime 2017-03-10 23:51
 */
public class ServerHandler extends ChannelHandlerAdapter {

    private static final Logger logger = LogManager.getLogger(ServerHandler.class);

    // 失败计数器：未收到client端发送的ping请求
    private int unRecPingTimes = 0;

    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        
        logger.debug("有客户端接入......");
        super.channelActive(ctx);
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        Channel channel = ctx.channel();

        Message message = (Message) msg;

        MessageHeader header = message.getHeader();

        byte chatType = header.getChatType();


        // 1、心跳,不做处理
        if (chatType == ChatType.PING) {

            ReferenceCountUtil.release(msg);

            return;
        }

        Map<String, Channel> channelMap = ChannelManager.getChannelMap();


        // 2、登录系统
        if (chatType == ChatType.LOGIN) {

            String fromId = String.valueOf(header.getFromId());

            String toId = String.valueOf(header.getToId());

            Channel ch = channelMap.get(fromId);

            Map<String,String> noticeMap = new HashMap<String,String>();

            // 断开连接
            if (channelMap.containsKey(fromId)) {

                noticeMap.put("status", "1000");
                noticeMap.put("message", "你被蹬下线了！");

                byte[] content = JSON.toJSONString(noticeMap).getBytes();

                message.setContent(content);
                header.setLength(content.length);

                header.setToId(Integer.valueOf(fromId));
                header.setFromId(Integer.valueOf(toId));

                message.setHeader(header);

                ChannelFuture f = ch.writeAndFlush(message);

                /**
                 * 注意：
                 * 此处需要同步获取执行结果后，再关闭通道，否则有可能数据未发送完毕，通道就关闭了
                 */
                if(f.sync().isSuccess()){
                    ch.close();
                }

                channelMap.remove(fromId);
            }

            logger.debug("pre--->"+channelMap.size());
            channelMap.put(fromId, channel);
            logger.debug("now--->"+channelMap.size());

            noticeMap.put("status", "1000");
            noticeMap.put("message", "你登录成功了！");

            byte[] content = JSON.toJSONString(noticeMap).getBytes();

            message.setContent(content);

            header.setLength(content.length);

            header.setToId(Integer.valueOf(fromId));
            header.setFromId(Integer.valueOf(toId));

            message.setHeader(header);

            channel.writeAndFlush(message);
        }

        //单聊
        else if(chatType == ChatType.CHAT){

            if(channel.isActive()){
                channel.writeAndFlush(message);
            }

            logger.debug("单聊");
        }

        // 3、群聊
        else if (chatType == ChatType.GROUP_CHAT) {
            Set<Map.Entry<String, Channel>> channelSet = channelMap.entrySet();

            Iterator<Map.Entry<String, Channel>> it = channelSet.iterator();

            Channel ch = null;

            while (it.hasNext()) {
                Map.Entry<String, Channel> entry = it.next();

                ch = entry.getValue();

                ch.writeAndFlush(message);
            }
        }

        // 4、广播
        else if (chatType == ChatType.BROADCAST) {
            Set<Map.Entry<String, Channel>> channelSet = channelMap.entrySet();

            Iterator<Map.Entry<String, Channel>> it = channelSet.iterator();

            Channel ch = null;

            while (it.hasNext()) {
                Map.Entry<String, Channel> entry = it.next();

                ch = entry.getValue();

                ch.writeAndFlush(message);
            }
        }

        // 显示释放内存
        ReferenceCountUtil.release(msg);
    }

    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

        logger.debug(String.format("有客户端[%s]异常退出......!", new Object[] { ChannelManager.getAccountNo(ctx.channel()) }));

        ChannelManager.removeChannel(ctx.channel());

        ctx.close();
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelInactive();
        ChannelManager.removeChannel(ctx.channel());
    }

    /** 心跳处理 */
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;

            if (event.state() == IdleState.ALL_IDLE) {

                if (unRecPingTimes >= PropertyUtils.MAX_UN_REC_PING_TIMES) {

                    logger.debug(String.format("用户[%s]读写超时，服务端关闭该通道！",new Object[] { ChannelManager.getAccountNo(ctx.channel()) }));

                    ChannelManager.removeChannel(ctx.channel());

                    // 连续超过N次未收到client的ping消息，那么关闭该通道，等待client重连
                    ctx.channel().close();
                } else {
                    // 失败计数器加1
                    unRecPingTimes++;
                }

            }
        }
    }

}
