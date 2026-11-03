package com.dsmnh.mobile.modserver.node;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 帧格式（HorizonProtocol 登记表 §1）：4B 大端 len + 1B type + 1B flags + payload。 */
public final class NodeProtocol {
    public static final int VERSION = 1;
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    public static final int T_HELLO = 0x01;
    public static final int T_HELLO_ACK = 0x02;
    public static final int T_AUTH = 0x03;
    public static final int T_AUTH_OK = 0x04;
    public static final int T_AUTH_FAIL = 0x05;
    public static final int T_REQ = 0x10;
    public static final int T_RES = 0x11;
    public static final int T_SUB = 0x20;
    public static final int T_SUB_OK = 0x21;
    public static final int T_PUSH = 0x22;
    public static final int T_UNSUB = 0x23;
    public static final int T_PING = 0x30;
    public static final int T_PONG = 0x31;
    public static final int T_ERR = 0x40;

    private NodeProtocol() {
    }

    /** 一条协议帧。 */
    public static final class Frame {
        public final int type;
        public final int flags;
        public final byte[] payload;

        public Frame(int type, int flags, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.payload = payload == null ? new byte[0] : payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** 读一帧；流正常结束返回 null，格式错误抛 IOException。 */
    public static Frame read(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len < 2 || len > MAX_FRAME) {
            throw new IOException("bad frame length: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new Frame(buf[0] & 0xFF, buf[1] & 0xFF, Arrays.copyOfRange(buf, 2, len));
    }

    /** 写一帧（调用方保证串行化）。 */
    public static void write(DataOutputStream out, int type, byte[] payload) throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        out.writeInt(body.length + 2);
        out.writeByte(type);
        out.writeByte(0);
        out.write(body);
        out.flush();
    }
}
