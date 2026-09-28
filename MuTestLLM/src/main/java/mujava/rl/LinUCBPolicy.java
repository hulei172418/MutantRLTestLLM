package mujava.rl;

import java.util.EnumMap;

public final class LinUCBPolicy implements BanditPolicy {
    private final double alpha;
    private final boolean functionBalance;
    private final LinUCBOperatorEncoding operatorEncoding;
    private final LinUCBPolicyStore store;
    private final EnumMap<EvidenceAction, LinUCBActionModel> models;

    public LinUCBPolicy(String modelPath, double alpha, double lambda, boolean functionBalance) {
        this(modelPath, alpha, lambda, functionBalance, LinUCBContextVectorizer.DEFAULT_ENCODING);
    }

    public LinUCBPolicy(String modelPath,
                        double alpha,
                        double lambda,
                        boolean functionBalance,
                        LinUCBOperatorEncoding operatorEncoding) {
        this.alpha = Math.max(0.0d, alpha);
        this.functionBalance = functionBalance;
        this.operatorEncoding = operatorEncoding == null ? LinUCBContextVectorizer.DEFAULT_ENCODING : operatorEncoding;
        this.store = new LinUCBPolicyStore(
                modelPath,
                LinUCBContextVectorizer.schemaVersion(this.operatorEncoding),
                LinUCBContextVectorizer.dimension(this.operatorEncoding),
                this.operatorEncoding.getConfigValue(),
                alpha,
                lambda);
        this.models = store.load();
    }

    @Override
    public synchronized EvidenceAction select(EvidenceState state) {
        double[] x = LinUCBContextVectorizer.vectorize(state, operatorEncoding);
        EvidenceAction best = EvidenceAction.BASELINE;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (EvidenceAction action : EvidenceAction.values()) {
            LinUCBActionModel model = models.get(action);
            double[] theta = solve(model.a, model.b);
            double exploit = dot(x, theta);
            double[] invAx = solve(model.a, x);
            double explore = alpha * Math.sqrt(Math.max(0.0d, dot(x, invAx)));
            double score = exploit + explore;
            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }
        if (state != null) {
            state.lastFallbackLevel = "LINUCB";
        }
        return best;
    }

    @Override
    public synchronized void update(EvidenceState state, EvidenceAction action, double reward) {
        if (action == null) {
            return;
        }
        double[] x = LinUCBContextVectorizer.vectorize(state, operatorEncoding);
        double weight = functionBalance ? LinUCBContextVectorizer.sampleWeight(state) : 1.0d;
        models.get(action).update(x, reward, weight);
        store.save(models);
    }

    private static double dot(double[] a, double[] b) {
        double out = 0.0d;
        for (int i = 0; i < a.length && i < b.length; i++) {
            out += a[i] * b[i];
        }
        return out;
    }

    static double[] solve(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n] = rhs[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12d) {
                continue;
            }
            if (pivot != col) {
                double[] tmp = a[pivot];
                a[pivot] = a[col];
                a[col] = tmp;
            }
            double div = a[col][col];
            for (int j = col; j <= n; j++) {
                a[col][j] /= div;
            }
            for (int row = 0; row < n; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                for (int j = col; j <= n; j++) {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = a[i][n];
        }
        return x;
    }
}
