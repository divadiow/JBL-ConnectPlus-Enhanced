package uk.co.divadiow.connectplusx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class JblProtocol {
    static final int COMPANY_ID = 87;

    static final int ACK = 0x00;
    static final int REQ_SPEAKER_INFO = 0x11;
    static final int RES_SPEAKER_INFO = 0x12;
    static final int SET_SPEAKER_INFO = 0x15;
    static final int PLAY_SOUND = 0x31;
    static final int REQ_DISCONNECT = 0x35;
    static final int REQ_FIRMWARE_VERSION = 0x41;
    static final int RES_FIRMWARE_VERSION = 0x42;
    static final int REQ_FEEDBACK_SOUNDS = 0x65;
    static final int RES_FEEDBACK_SOUNDS = 0x66;
    static final int SET_FEEDBACK_SOUNDS = 0x67;
    static final int REQ_SPEAKERPHONE_MODE = 0x68;
    static final int RES_SPEAKERPHONE_MODE = 0x69;
    static final int SET_SPEAKERPHONE_MODE = 0x70;
    static final int SET_BASS_LEVEL = 0x76;
    static final int REQ_BASS_LEVEL = 0x77;
    static final int RES_BASS_LEVEL = 0x78;

    static final int TOKEN_MODEL = 0x42;
    static final int TOKEN_COLOR = 0x43;
    static final int TOKEN_BATTERY = 0x44;
    static final int TOKEN_LINKED_COUNT = 0x45;
    static final int TOKEN_AUDIO_CHANNEL = 0x46;
    static final int TOKEN_AUDIO_SOURCE = 0x47;
    static final int TOKEN_MAC = 0x48;
    static final int TOKEN_NAME = 0xC1;

    static byte[] frame(int type, byte... payload) {
        if (payload.length > 255) throw new IllegalArgumentException("Payload too long");
        byte[] out = new byte[payload.length + 3];
        out[0] = (byte) 0xAA;
        out[1] = (byte) type;
        out[2] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 3, payload.length);
        return out;
    }

    static boolean shouldResynchronise(ByteArrayOutputStream pending, byte[] incoming) {
        byte[] previous = pending.toByteArray();
        if (previous.length < 3 || incoming.length < 3) return false;
        if (u8(previous[0]) != 0xAA || u8(incoming[0]) != 0xAA) return false;
        int previousFrameLength = u8(previous[2]) + 3;
        if (previous.length >= previousFrameLength) return false;
        boolean sameHeader = previous[0] == incoming[0]
                && previous[1] == incoming[1]
                && previous[2] == incoming[2];
        int incomingFrameLength = u8(incoming[2]) + 3;
        boolean incomingIsCompleteFrame = incoming.length >= incomingFrameLength;
        return sameHeader || incomingIsCompleteFrame;
    }

    static List<Packet> decodeFrames(ByteArrayOutputStream pending, byte[] incoming) {
        // A JBL response is normally one characteristic value after MTU negotiation.
        // If an incomplete/truncated value is followed by a fresh AA header, do not
        // concatenate the two starts into a synthetic frame.
        if (shouldResynchronise(pending, incoming)) pending.reset();
        try {
            pending.write(incoming);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] all = pending.toByteArray();
        List<Packet> result = new ArrayList<>();
        int pos = 0;
        while (all.length - pos >= 3) {
            if ((all[pos] & 0xFF) != 0xAA) {
                pos++;
                continue;
            }
            int length = all[pos + 2] & 0xFF;
            int frameLength = length + 3;
            if (all.length - pos < frameLength) break;
            result.add(new Packet(all[pos + 1] & 0xFF,
                    Arrays.copyOfRange(all, pos + 3, pos + frameLength)));
            pos += frameLength;
        }
        pending.reset();
        if (pos < all.length) {
            pending.write(all, pos, all.length - pos);
        }
        if (pending.size() > 512) pending.reset();
        return result;
    }

    static void apply(Packet packet, SpeakerState state, DiagnosticLog log) {
        byte[] p = packet.payload;
        switch (packet.type) {
            case RES_SPEAKER_INFO -> parseSpeakerInfo(p, state, log);
            case RES_FIRMWARE_VERSION -> {
                if (p.length == 0) return;
                if (p.length <= 2) {
                    state.firmware = ((p[0] >> 4) & 0x0F) + "." + (p[0] & 0x0F);
                } else {
                    state.firmware = u8(p[0]) + "." + u8(p[1]) + "." + u8(p[2]);
                }
            }
            case RES_FEEDBACK_SOUNDS -> {
                if (p.length >= 1) state.feedbackSounds = p[0] == 1;
            }
            case RES_SPEAKERPHONE_MODE -> {
                if (p.length >= 1) state.speakerphone = p[0] == 1;
            }
            case RES_BASS_LEVEL -> {
                if (p.length >= 1) state.bassLevel = u8(p[0]);
            }
            case ACK -> { }
            default -> log.add("RX unhandled packet 0x" + hexByte(packet.type) + " " + hex(p));
        }
    }

    private static void parseSpeakerInfo(byte[] p, SpeakerState state, DiagnosticLog log) {
        if (p.length == 0) return;
        state.index = u8(p[0]);
        int i = 1;
        while (i < p.length) {
            int token = u8(p[i++]);
            switch (token) {
                case TOKEN_MODEL -> {
                    if (!has(p, i, 2)) return;
                    state.modelId = (u8(p[i]) << 8) | u8(p[i + 1]);
                    state.modelName = ModelInfo.nameFor(state.modelId);
                    i += 2;
                }
                case TOKEN_COLOR -> {
                    if (!has(p, i, 1)) return;
                    state.colorId = u8(p[i++]);
                }
                case TOKEN_BATTERY -> {
                    if (!has(p, i, 1)) return;
                    int status = u8(p[i++]);
                    state.charging = status > 100;
                    state.battery = status % 128;
                }
                case TOKEN_LINKED_COUNT -> {
                    if (!has(p, i, 1)) return;
                    state.linkedCount = u8(p[i++]);
                }
                case TOKEN_AUDIO_CHANNEL -> {
                    if (!has(p, i, 1)) return;
                    state.audioChannel = u8(p[i++]);
                }
                case TOKEN_AUDIO_SOURCE -> {
                    if (!has(p, i, 1)) return;
                    state.playing = u8(p[i++]) == 1;
                }
                case TOKEN_MAC -> {
                    if (!has(p, i, 7)) return;
                    i += 7;
                }
                case TOKEN_NAME -> {
                    if (!has(p, i, 1)) return;
                    int length = u8(p[i++]);
                    if (!has(p, i, length)) return;
                    state.name = new String(p, i, length, StandardCharsets.UTF_8);
                    i += length;
                }
                default -> {
                    log.add("Unknown speaker-info token 0x" + hexByte(token)
                            + "; remaining=" + hex(Arrays.copyOfRange(p, i, p.length))
                            + "; parser stopped safely");
                    return;
                }
            }
        }
    }

    static byte[] setChannel(int speakerIndex, int channel) {
        if (channel < 0 || channel > 2) throw new IllegalArgumentException("Channel must be 0, 1 or 2");
        return frame(SET_SPEAKER_INFO, (byte) speakerIndex, (byte) TOKEN_AUDIO_CHANNEL, (byte) channel);
    }

    static byte[] setName(int speakerIndex, String name) {
        byte[] text = name.trim().getBytes(StandardCharsets.UTF_8);
        if (text.length == 0 || text.length > 32) throw new IllegalArgumentException("Name must be 1-32 UTF-8 bytes");
        byte[] payload = new byte[text.length + 3];
        payload[0] = (byte) speakerIndex;
        payload[1] = (byte) TOKEN_NAME;
        payload[2] = (byte) text.length;
        System.arraycopy(text, 0, payload, 3, text.length);
        return frame(SET_SPEAKER_INFO, payload);
    }

    static int u8(byte value) { return value & 0xFF; }
    static boolean has(byte[] bytes, int offset, int count) { return count >= 0 && offset >= 0 && offset + count <= bytes.length; }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hexByte(b & 0xFF));
        }
        return sb.toString();
    }

    private static String hexByte(int value) {
        return String.format("%02X", value & 0xFF);
    }

    record Packet(int type, byte[] payload) { }
}
