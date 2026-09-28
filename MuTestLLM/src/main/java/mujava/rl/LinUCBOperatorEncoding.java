package mujava.rl;

public enum LinUCBOperatorEncoding {
    SIX_CLASS("six_class"),
    ONE_HOT_19("one_hot_19");

    private final String configValue;

    LinUCBOperatorEncoding(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static LinUCBOperatorEncoding parse(String value) {
        if (value == null) {
            return SIX_CLASS;
        }
        String normalized = value.trim().toLowerCase();
        for (LinUCBOperatorEncoding encoding : values()) {
            if (encoding.configValue.equals(normalized)) {
                return encoding;
            }
        }
        return SIX_CLASS;
    }
}
