package mujava.rl;

final class LinUCBOperatorFeatures {
    private static final String[] ONE_HOT_OPERATORS = new String[]{
            "AOIS", "SDL", "ROR", "AORB", "ODL", "LOI", "AOIU", "COI", "VDL", "CDL",
            "COR", "ASRS", "AORS", "COD", "LOR", "AODU", "AODS", "LOD", "SOR", "OTHER"
    };

    private static final String[] SIX_CLASS_LABELS = new String[]{
            "NUMERIC_EXPR",
            "RELATIONAL_BRANCH",
            "BOOLEAN_CONDITION",
            "BITWISE_SHIFT",
            "STATE_UPDATE_MISSING",
            "MIXED_DELETE_SIMPLIFY"
    };

    private LinUCBOperatorFeatures() {
    }

    static int dimension(LinUCBOperatorEncoding encoding) {
        return labels(encoding).length;
    }

    static void encode(String operatorFamily, LinUCBOperatorEncoding encoding, double[] target, int offset) {
        String normalized = upper(operatorFamily);
        switch (encoding) {
            case ONE_HOT_19:
                encodeOneHot19(normalized, target, offset);
                return;
            case SIX_CLASS:
            default:
                encodeSixClass(normalized, target, offset);
                return;
        }
    }

    private static void encodeSixClass(String operatorFamily, double[] target, int offset) {
        String family = sixClassFamily(operatorFamily);
        String[] labels = SIX_CLASS_LABELS;
        for (int i = 0; i < labels.length; i++) {
            target[offset + i] = labels[i].equals(family) ? 1.0d : 0.0d;
        }
    }

    private static void encodeOneHot19(String operatorFamily, double[] target, int offset) {
        String[] labels = ONE_HOT_OPERATORS;
        String match = operatorFamily;
        boolean known = false;
        for (int i = 0; i < labels.length - 1; i++) {
            if (labels[i].equals(match)) {
                target[offset + i] = 1.0d;
                known = true;
            } else {
                target[offset + i] = 0.0d;
            }
        }
        target[offset + labels.length - 1] = known ? 0.0d : 1.0d;
    }

    static int schemaVersion(LinUCBOperatorEncoding encoding) {
        switch (encoding) {
            case ONE_HOT_19:
                return 3;
            case SIX_CLASS:
            default:
                return 2;
        }
    }

    static String[] labels(LinUCBOperatorEncoding encoding) {
        switch (encoding) {
            case ONE_HOT_19:
                return ONE_HOT_OPERATORS;
            case SIX_CLASS:
            default:
                return SIX_CLASS_LABELS;
        }
    }

    private static String sixClassFamily(String operatorFamily) {
        if (operatorFamily == null || operatorFamily.trim().isEmpty()) {
            return "MIXED_DELETE_SIMPLIFY";
        }
        if ("AORB".equals(operatorFamily)
                || "AORS".equals(operatorFamily)
                || "AOIS".equals(operatorFamily)
                || "AOIU".equals(operatorFamily)
                || "ASRS".equals(operatorFamily)) {
            return "NUMERIC_EXPR";
        }
        if ("ROR".equals(operatorFamily)) {
            return "RELATIONAL_BRANCH";
        }
        if ("COI".equals(operatorFamily) || "COR".equals(operatorFamily)) {
            return "BOOLEAN_CONDITION";
        }
        if ("LOI".equals(operatorFamily) || "LOR".equals(operatorFamily) || "SOR".equals(operatorFamily)) {
            return "BITWISE_SHIFT";
        }
        if ("SDL".equals(operatorFamily) || "VDL".equals(operatorFamily)) {
            return "STATE_UPDATE_MISSING";
        }
        return "MIXED_DELETE_SIMPLIFY";
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
