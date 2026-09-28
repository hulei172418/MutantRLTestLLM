package mujava.rl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime configuration for the first-stage RL loop.
 */
public final class RLPipelineConfig {
    private static final String DEFAULT_LOG_PATH = "logs/rl/trajectory.jsonl";
    private static final String DEFAULT_POLICY_STORE_PATH = "logs/rl/policy-store.json";
    private static final String DEFAULT_FEEDBACK_LOG_PATH = "logs/rl/failure-feedback.jsonl";
    private static final String DEFAULT_TRANSITION_LOG_PATH = "logs/rl/transitions.jsonl";
    private static final String DEFAULT_EPISODE_LOG_PATH = "logs/rl/episodes.jsonl";
    private static final String DEFAULT_LINUCB_MODEL_PATH = "logs/rl/linucb-policy.json";

    private boolean enabled;
    private String mode;
    private String policy;
    private double epsilon;
    private String logPath;
    private String policyStorePath;
    private String feedbackLogPath;
    private String transitionLogPath;
    private String episodeLogPath;
    private EvidenceAction warmupAction;
    private int bucketMinSamplesExact;
    private int bucketMinSamplesCoarse;
    private boolean regenerationEnabled;
    private int regenerationMaxRounds;
    private boolean referenceRegenerationEnabled;
    private int referenceRegenerationMaxReferences;
    private boolean historyEnabled;
    private boolean historyReferenceEnabled;
    private int historyReferenceMaxReferences;
    private double linucbAlpha;
    private double linucbLambda;
    private boolean linucbFunctionBalance;
    private String linucbModelPath;
    private LinUCBOperatorEncoding linucbOperatorEncoding;

    public boolean isEnabled() {
        return enabled;
    }

    public String getMode() {
        return mode;
    }

    public String getPolicy() {
        return policy;
    }

    public double getEpsilon() {
        return epsilon;
    }

    public String getLogPath() {
        return logPath;
    }

    public String getPolicyStorePath() {
        return policyStorePath;
    }

    public String getFeedbackLogPath() {
        return feedbackLogPath;
    }

    public String getTransitionLogPath() {
        return transitionLogPath;
    }

    public String getEpisodeLogPath() {
        return episodeLogPath;
    }

    public EvidenceAction getWarmupAction() {
        return warmupAction;
    }

    public int getBucketMinSamplesExact() {
        return bucketMinSamplesExact;
    }

    public int getBucketMinSamplesCoarse() {
        return bucketMinSamplesCoarse;
    }

    public boolean isRegenerationEnabled() {
        return regenerationEnabled;
    }

    public int getRegenerationMaxRounds() {
        return regenerationMaxRounds;
    }

    public boolean isReferenceRegenerationEnabled() {
        return referenceRegenerationEnabled;
    }

    public int getReferenceRegenerationMaxReferences() {
        return referenceRegenerationMaxReferences;
    }

    public boolean isHistoryEnabled() {
        return historyEnabled;
    }

    public boolean isHistoryReferenceEnabled() {
        return historyReferenceEnabled;
    }

    public int getHistoryReferenceMaxReferences() {
        return historyReferenceMaxReferences;
    }

    public double getLinucbAlpha() {
        return linucbAlpha;
    }

    public double getLinucbLambda() {
        return linucbLambda;
    }

    public boolean isLinucbFunctionBalance() {
        return linucbFunctionBalance;
    }

    public String getLinucbModelPath() {
        return linucbModelPath;
    }

    public LinUCBOperatorEncoding getLinucbOperatorEncoding() {
        return linucbOperatorEncoding;
    }

    public static RLPipelineConfig load() {
        Properties props = loadProperties("llm.properties");
        RLPipelineConfig cfg = new RLPipelineConfig();
        cfg.enabled = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_ENABLED"),
                System.getProperty("rl.enabled"),
                props.getProperty("rl.enabled"),
                "false"
        ));
        cfg.mode = firstNonBlank(
                System.getenv("RL_MODE"),
                System.getProperty("rl.mode"),
                props.getProperty("rl.mode"),
                "bandit"
        );
        cfg.policy = firstNonBlank(
                System.getenv("RL_BANDIT_POLICY"),
                System.getProperty("rl.bandit.policy"),
                props.getProperty("rl.bandit.policy"),
                System.getenv("RL_POLICY"),
                System.getProperty("rl.policy"),
                props.getProperty("rl.policy"),
                "epsilon_greedy"
        );
        cfg.epsilon = parseDouble(firstNonBlank(
                System.getenv("RL_EPSILON"),
                System.getProperty("rl.epsilon"),
                props.getProperty("rl.epsilon"),
                "0.1"
        ), 0.1d);
        cfg.logPath = firstNonBlank(
                System.getenv("RL_LOG_PATH"),
                System.getProperty("rl.log.path"),
                props.getProperty("rl.log.path"),
                DEFAULT_LOG_PATH
        );
        cfg.policyStorePath = firstNonBlank(
                System.getenv("RL_POLICY_STORE_PATH"),
                System.getProperty("rl.policy.store.path"),
                props.getProperty("rl.policy.store.path"),
                DEFAULT_POLICY_STORE_PATH
        );
        cfg.feedbackLogPath = firstNonBlank(
                System.getenv("RL_FEEDBACK_LOG_PATH"),
                System.getProperty("rl.feedback.log.path"),
                props.getProperty("rl.feedback.log.path"),
                DEFAULT_FEEDBACK_LOG_PATH
        );
        cfg.transitionLogPath = firstNonBlank(
                System.getenv("RL_TRANSITION_LOG_PATH"),
                System.getProperty("rl.transition.log.path"),
                props.getProperty("rl.transition.log.path"),
                DEFAULT_TRANSITION_LOG_PATH
        );
        cfg.episodeLogPath = firstNonBlank(
                System.getenv("RL_EPISODE_LOG_PATH"),
                System.getProperty("rl.episode.log.path"),
                props.getProperty("rl.episode.log.path"),
                DEFAULT_EPISODE_LOG_PATH
        );
        cfg.warmupAction = parseAction(firstNonBlank(
                System.getenv("RL_WARMUP_ACTION"),
                System.getProperty("rl.warmup.action"),
                props.getProperty("rl.warmup.action"),
                EvidenceAction.BASELINE.name()
        ));
        cfg.bucketMinSamplesExact = parseInt(firstNonBlank(
                System.getenv("RL_BUCKET_MIN_SAMPLES_EXACT"),
                System.getProperty("rl.bucket.min.samples.exact"),
                props.getProperty("rl.bucket.min.samples.exact"),
                "2"
        ), 2);
        cfg.bucketMinSamplesCoarse = parseInt(firstNonBlank(
                System.getenv("RL_BUCKET_MIN_SAMPLES_COARSE"),
                System.getProperty("rl.bucket.min.samples.coarse"),
                props.getProperty("rl.bucket.min.samples.coarse"),
                "4"
        ), 4);
        cfg.regenerationEnabled = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_REGENERATION_ENABLED"),
                System.getProperty("rl.regeneration.enabled"),
                props.getProperty("rl.regeneration.enabled"),
                "true"
        ));
        cfg.regenerationMaxRounds = parseInt(firstNonBlank(
                System.getenv("RL_REGENERATION_MAX_ROUNDS"),
                System.getProperty("rl.regeneration.max.rounds"),
                props.getProperty("rl.regeneration.max.rounds"),
                "1"
        ), 1);
        cfg.referenceRegenerationEnabled = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_REFERENCE_REGENERATION_ENABLED"),
                System.getProperty("rl.reference.regeneration.enabled"),
                props.getProperty("rl.reference.regeneration.enabled"),
                "true"
        ));
        cfg.referenceRegenerationMaxReferences = parseInt(firstNonBlank(
                System.getenv("RL_REFERENCE_REGENERATION_MAX_REFERENCES"),
                System.getProperty("rl.reference.regeneration.max.references"),
                props.getProperty("rl.reference.regeneration.max.references"),
                "2"
        ), 2);
        cfg.historyEnabled = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_HISTORY_ENABLED"),
                System.getProperty("rl.history.enabled"),
                props.getProperty("rl.history.enabled"),
                "true"
        ));
        cfg.historyReferenceEnabled = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_HISTORY_REFERENCE_ENABLED"),
                System.getProperty("rl.history.reference.enabled"),
                props.getProperty("rl.history.reference.enabled"),
                System.getenv("RL_REFERENCE_REGENERATION_ENABLED"),
                System.getProperty("rl.reference.regeneration.enabled"),
                props.getProperty("rl.reference.regeneration.enabled"),
                "true"
        ));
        cfg.historyReferenceMaxReferences = parseInt(firstNonBlank(
                System.getenv("RL_HISTORY_REFERENCE_MAX_REFERENCES"),
                System.getProperty("rl.history.reference.max.references"),
                props.getProperty("rl.history.reference.max.references"),
                System.getenv("RL_REFERENCE_REGENERATION_MAX_REFERENCES"),
                System.getProperty("rl.reference.regeneration.max.references"),
                props.getProperty("rl.reference.regeneration.max.references"),
                "2"
        ), 2);
        cfg.linucbAlpha = parseDouble(firstNonBlank(
                System.getenv("RL_LINUCB_ALPHA"),
                System.getProperty("rl.linucb.alpha"),
                props.getProperty("rl.linucb.alpha"),
                "0.5"
        ), 0.5d);
        cfg.linucbLambda = parseDouble(firstNonBlank(
                System.getenv("RL_LINUCB_LAMBDA"),
                System.getProperty("rl.linucb.lambda"),
                props.getProperty("rl.linucb.lambda"),
                "1.0"
        ), 1.0d);
        cfg.linucbFunctionBalance = Boolean.parseBoolean(firstNonBlank(
                System.getenv("RL_LINUCB_FUNCTION_BALANCE"),
                System.getProperty("rl.linucb.function.balance"),
                props.getProperty("rl.linucb.function.balance"),
                "true"
        ));
        cfg.linucbModelPath = firstNonBlank(
                System.getenv("RL_LINUCB_MODEL_PATH"),
                System.getProperty("rl.linucb.model.path"),
                props.getProperty("rl.linucb.model.path"),
                DEFAULT_LINUCB_MODEL_PATH
        );
        cfg.linucbOperatorEncoding = LinUCBOperatorEncoding.parse(firstNonBlank(
                System.getenv("RL_LINUCB_OPERATOR_ENCODING"),
                System.getProperty("rl.linucb.operator.encoding"),
                props.getProperty("rl.linucb.operator.encoding"),
                LinUCBContextVectorizer.DEFAULT_ENCODING.getConfigValue()
        ));
        return cfg;
    }

    private static EvidenceAction parseAction(String value) {
        try {
            return EvidenceAction.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            return EvidenceAction.BASELINE;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Properties loadProperties(String resourceName) {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file: " + resourceName, e);
        }
        return props;
    }
}
