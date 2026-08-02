package uk.co.divadiow.connectplusx;

final class ModelInfo {
    static final int FLIP3 = 0x0023;
    static final int CHARGE3 = 0x1EBC;
    static final int FLIP4 = 0x1ED1;
    static final int CHARGE4 = 0x1F17;

    static String nameFor(int id) {
        return switch (id) {
            case FLIP3 -> "JBL Flip 3";
            case CHARGE3, 0x1F25 -> "JBL Charge 3";
            case FLIP4, 0x1F24 -> "JBL Flip 4";
            case CHARGE4, 0x1F29 -> "JBL Charge 4";
            case 0x0024 -> "JBL Xtreme";
            case 0x0026 -> "JBL Pulse 2";
            case 0x1ED2, 0x1F28 -> "JBL Pulse 3";
            case 0x1EE7, 0x1F27 -> "JBL Boombox";
            case 0x1EFC, 0x1F26, 0x2038 -> "JBL Xtreme 2";
            default -> String.format("JBL model 0x%04X", id);
        };
    }

    static boolean isPrimaryTarget(int id) {
        return id == FLIP3 || id == FLIP4 || id == 0x1F24 || id == CHARGE3 || id == CHARGE4 || id == 0x1F25 || id == 0x1F29;
    }

    static String generation(int id) {
        if (id == FLIP3 || id == 0x0024 || id == 0x0026) return "JBL Connect / CSR";
        if (id == 0x1F24 || id == 0x1F25 || id == 0x1F29) return "Connect+ / QCC variant";
        return "JBL Connect+ / CSR";
    }

    private ModelInfo() { }
}
