package uk.co.divadiow.connectplusx;

final class SpeakerState {
    int index;
    int modelId;
    int colorId;
    int battery = -1;
    int linkedCount = -1;
    int audioChannel = 0;
    int bassLevel = -1;
    boolean charging;
    boolean playing;
    Boolean feedbackSounds;
    Boolean speakerphone;
    String name = "Unknown JBL";
    String modelName = "Unknown JBL";
    String firmware = "Unknown";

    String summary() {
        String batteryText = battery < 0 ? "unknown" : battery + "%" + (charging ? " charging" : "");
        String channel = switch (audioChannel) {
            case 1 -> "left";
            case 2 -> "right";
            default -> "stereo";
        };
        return modelName + " · " + name + "\nFirmware " + firmware + " · Battery " + batteryText
                + " · Channel " + channel + (linkedCount >= 0 ? " · Linked " + linkedCount : "");
    }
}
