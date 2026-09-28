package mujava.rl;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;

final class LinUCBPolicyStore {
    private final Path file;
    private final int schemaVersion;
    private final int dimension;
    private final String operatorEncoding;
    private final double alpha;
    private final double lambda;

    LinUCBPolicyStore(String path, int schemaVersion, int dimension, String operatorEncoding, double alpha, double lambda) {
        this.file = Paths.get(path).toAbsolutePath().normalize();
        this.schemaVersion = schemaVersion;
        this.dimension = dimension;
        this.operatorEncoding = operatorEncoding == null ? "" : operatorEncoding;
        this.alpha = alpha;
        this.lambda = lambda;
    }

    EnumMap<EvidenceAction, LinUCBActionModel> load() {
        EnumMap<EvidenceAction, LinUCBActionModel> models = newModels();
        if (!Files.isRegularFile(file)) {
            return models;
        }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            int version = root.optInt("stateSchemaVersion", -1);
            int dim = root.optInt("featureDimension", -1);
            String encoding = root.optString("operatorEncoding", "");
            if (version != schemaVersion || dim != dimension || !operatorEncoding.equals(encoding)) {
                throw new IllegalStateException("LinUCB schema mismatch: fileVersion=" + version
                        + ", expectedVersion=" + schemaVersion + ", fileDimension=" + dim
                        + ", expectedDimension=" + dimension + ", fileEncoding=" + encoding
                        + ", expectedEncoding=" + operatorEncoding);
            }
            JSONObject actions = root.optJSONObject("actions");
            if (actions == null) {
                return models;
            }
            for (EvidenceAction action : EvidenceAction.values()) {
                models.put(action, LinUCBActionModel.fromJson(actions.optJSONObject(action.name()), dimension, lambda));
            }
            return models;
        } catch (Throwable t) {
            System.err.println("[LINUCB-WARN] failed to load policy store: " + file + " ; " + t);
            return models;
        }
    }

    void save(EnumMap<EvidenceAction, LinUCBActionModel> models) {
        try {
            Files.createDirectories(file.getParent());
            JSONObject root = new JSONObject();
            root.put("policyType", "linucb");
            root.put("stateSchemaVersion", schemaVersion);
            root.put("featureDimension", dimension);
            root.put("operatorEncoding", operatorEncoding);
            root.put("alpha", alpha);
            root.put("lambda", lambda);
            JSONObject actions = new JSONObject();
            for (EvidenceAction action : EvidenceAction.values()) {
                actions.put(action.name(), models.get(action).toJson());
            }
            root.put("actions", actions);

            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, root.toString(2).getBytes(StandardCharsets.UTF_8));
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName().toString() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save LinUCB policy store: " + file, e);
        }
    }

    private EnumMap<EvidenceAction, LinUCBActionModel> newModels() {
        EnumMap<EvidenceAction, LinUCBActionModel> models =
                new EnumMap<EvidenceAction, LinUCBActionModel>(EvidenceAction.class);
        for (EvidenceAction action : EvidenceAction.values()) {
            models.put(action, new LinUCBActionModel(dimension, lambda));
        }
        return models;
    }
}
