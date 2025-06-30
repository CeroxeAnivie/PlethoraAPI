package plethora.net;

import java.io.Serializable;

public class DataPacket implements Serializable {
    public DataPacket(long realLen, byte[] enData) {
        this.enData = enData;
        this.realLen = realLen;
    }

    public long realLen;
    public byte[] enData;
}
