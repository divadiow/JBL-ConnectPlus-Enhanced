package uk.co.divadiow.connectplusx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class JblProtocolTest {
    @Test public void framesChannelCommandExactly() {
        assertArrayEquals(new byte[] {(byte) 0xAA, 0x15, 0x03, 0x00, 0x46, 0x01},
                JblProtocol.setChannel(0, 1));
    }

    @Test public void buffersSplitNotificationFrames() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        assertEquals(0, JblProtocol.decodeFrames(pending,
                new byte[] {(byte) 0xAA, 0x42}).size());
        List<JblProtocol.Packet> packets = JblProtocol.decodeFrames(pending,
                new byte[] {0x03, 0x01, 0x02, 0x03});
        assertEquals(1, packets.size());
        assertEquals(0x42, packets.get(0).type());
        assertArrayEquals(new byte[] {1, 2, 3}, packets.get(0).payload());
    }

    @Test public void replacesRepeatedTruncatedFrameStartInsteadOfSynthesisingFrame() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        byte[] truncated = new byte[] {(byte) 0xAA, 0x12, 0x21, 0x00, (byte) 0xC1, 0x0C,
                0x4A, 0x42, 0x4C, 0x20, 0x43, 0x68, 0x61, 0x72, 0x67, 0x65, 0x20, 0x34, 0x42, 0x1F};
        assertEquals(0, JblProtocol.decodeFrames(pending, truncated).size());
        assertEquals(0, JblProtocol.decodeFrames(pending, truncated).size());
        assertEquals(truncated.length, pending.size());
    }

    @Test public void dropsIncompleteFrameWhenACompleteNewFrameArrives() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        assertEquals(0, JblProtocol.decodeFrames(pending,
                new byte[] {(byte) 0xAA, 0x12, 0x21, 0x00}).size());
        List<JblProtocol.Packet> packets = JblProtocol.decodeFrames(pending,
                new byte[] {(byte) 0xAA, 0x42, 0x03, 0x03, 0x04, 0x00});
        assertEquals(1, packets.size());
        assertEquals(0x42, packets.get(0).type());
    }

    @Test public void skipsNoiseBeforeValidFrame() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        List<JblProtocol.Packet> packets = JblProtocol.decodeFrames(pending,
                new byte[] {0x55, 0x66, (byte) 0xAA, 0x66, 0x01, 0x01});
        assertEquals(1, packets.size());
        assertEquals(0x66, packets.get(0).type());
    }
}
