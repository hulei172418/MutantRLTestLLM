package mujava.rl;

/**
 * Fixed-schema vectorizer for Disjoint LinUCB. Bump SCHEMA_VERSION whenever the
 * feature order or dimension changes.
 */
public final class LinUCBContextVectorizer {
    public static final LinUCBOperatorEncoding DEFAULT_ENCODING = LinUCBOperatorEncoding.SIX_CLASS;
    public static final int SCHEMA_VERSION = schemaVersion(DEFAULT_ENCODING);
    public static final int DIMENSION = dimension(DEFAULT_ENCODING);

    private LinUCBContextVectorizer() {
    }

    public static double[] vectorize(EvidenceState state) {
        return vectorize(state, DEFAULT_ENCODING);
    }

    public static double[] vectorize(EvidenceState state, LinUCBOperatorEncoding encoding) {
        LinUCBOperatorEncoding effectiveEncoding = encoding == null ? DEFAULT_ENCODING : encoding;
        double[] x = new double[dimension(effectiveEncoding)];
        int i = 0;
        x[i++] = 1.0d;
        x[i++] = bool(state != null && state.useReflectionFallback);
        x[i++] = bool(state != null && state.skipTestGeneration);
        x[i++] = bool(state != null && state.needEntryLiftedEvidence);
        x[i++] = bool(state != null && state.hasSuccessfulReference);
        x[i++] = bool(state != null && state.hasCodeKbContext);
        x[i++] = normCount(state == null ? 0 : state.codeKbEntryCount, 20);
        x[i++] = normCount(state == null ? 0 : state.codeKbMethodCallCount, 100);
        x[i++] = normCount(state == null ? 0 : state.codeKbFieldAccessCount, 100);
        x[i++] = normCount(state == null ? 0 : state.estimatedPromptSize, 60000);
        x[i++] = normCount(state == null ? 0 : state.functionMutantCount, 500);
        x[i++] = normCount(state == null ? 0 : state.siteMutantCount, 100);
        x[i++] = ratio(state == null ? 0 : state.functionProcessedCount, state == null ? 0 : state.functionMutantCount);
        x[i++] = ratio(state == null ? 0 : state.siteProcessedCount, state == null ? 0 : state.siteMutantCount);

        LinUCBOperatorFeatures.encode(state == null ? "" : state.operatorFamily, effectiveEncoding, x, i);
        i += LinUCBOperatorFeatures.dimension(effectiveEncoding);

        String observable = upper(state == null ? "" : state.observablePlanKind);
        x[i++] = bool(observable.contains("EXCEPTION"));
        x[i++] = bool(observable.contains("RETURN"));
        x[i++] = bool(observable.contains("FIELD") || observable.contains("STATE"));

        String receiver = upper(state == null ? "" : state.testReceiverStrategy);
        x[i++] = bool(receiver.contains("CONSTRUCTOR") || receiver.contains("NEW"));
        x[i++] = bool(receiver.contains("REFLECTION"));
        x[i++] = bool(state != null && state.codeKbOwnerInstantiable);
        x[i++] = bool(state != null && isPublicLike(state.codeKbOwnerVisibility));
        x[i++] = bool(state != null && state.codeKbHasAccessibleConstructor);
        x[i++] = bool(state != null && state.codeKbHasStaticFactory);
        x[i++] = bool(state != null && state.codeKbHasStaticMethod);
        x[i++] = bool(state != null && state.codeKbHasInstanceMethod);
        x[i++] = bool(state != null && state.codeKbHasOnlyFactoryConstruction);
        x[i++] = normCount(state == null ? 0 : state.codeKbExactCallableSignatureCount, 40);
        x[i++] = normCount(state == null ? 0 : state.codeKbForbiddenCallCount, 20);

        x[i++] = bool(state != null && state.forcedBranchMutation);
        x[i++] = bool(state != null && state.hasCanonicalObservable);
        x[i++] = bool(state != null && state.hasMandatoryInfectionConstraints);
        x[i++] = bool(state != null && state.hasRequiredNonNullSubjects);
        x[i++] = bool(state != null && state.hasExpectedOriginalExecutable);
        x[i++] = clamp01(state == null ? 0.0d : state.entryIndependentCoverage);
        x[i++] = bool(state != null && isUnknownOrUnsat(state.entryConstraintStatus));
        x[i++] = normCount(state == null ? 0 : state.mandatoryGuardCount, 12);
        x[i++] = bool(state != null && state.hasFalsePolarityGuard);
        x[i++] = bool(state != null && state.mandatoryCofactorCount > 0);
        x[i++] = normCount(state == null ? 0 : state.mandatoryCofactorCount, 8);
        x[i++] = observableDirectnessScore(state == null ? "" : state.observableDirectness);
        x[i++] = clamp01(state == null ? 0.0d : state.entryControllabilityCoverage);

        // Prior-round historical experience. These features let LinUCB distinguish
        // a genuinely new mutant from one that already failed compilation or has
        // strong same-site/same-method sibling successes available.
        x[i++] = bool(state != null && state.hasPreviousResult);
        x[i++] = bool(state != null && state.previousCompileSuccess);
        x[i++] = bool(state != null && state.previousOriginalPassed);
        x[i++] = bool(state != null && state.previousKilled);
        x[i++] = normCount(state == null ? 0 : state.previousRepairRounds, 4);
        x[i++] = normCount(state == null ? 0 : state.successfulReferenceCount, 4);
        x[i++] = normCount(state == null ? 0 : state.sameSiteSuccessfulCount, 20);
        x[i++] = normCount(state == null ? 0 : state.sameMethodSuccessfulCount, 60);
        x[i++] = clamp01(state == null ? 0.0d : state.sameSiteCompileSuccessRate);
        x[i++] = clamp01(state == null ? 0.0d : state.sameSiteKillRate);
        x[i++] = clamp01(state == null ? 0.0d : state.sameMethodCompileSuccessRate);
        x[i++] = clamp01(state == null ? 0.0d : state.sameMethodKillRate);
        x[i++] = clamp01((state == null ? 0.0d : state.bestSuccessfulReferenceSimilarity) / 130.0d);
        return x;
    }

    public static int schemaVersion(LinUCBOperatorEncoding encoding) {
        return LinUCBOperatorFeatures.schemaVersion(encoding) + 4;
    }

    public static int dimension(LinUCBOperatorEncoding encoding) {
        return 14 + LinUCBOperatorFeatures.dimension(encoding) + 3 + 2 + 9 + 13 + 13;
    }

    public static double sampleWeight(EvidenceState state) {
        int n = state == null ? 0 : state.functionMutantCount;
        return 1.0d / Math.sqrt(Math.max(1, n));
    }

    private static double bool(boolean value) {
        return value ? 1.0d : 0.0d;
    }

    private static double normCount(int count, int max) {
        int c = Math.max(0, count);
        int m = Math.max(1, max);
        return Math.log(1.0d + c) / Math.log(1.0d + m);
    }

    private static double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, numerator * 1.0d / denominator));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static boolean isUnknownOrUnsat(String status) {
        String normalized = upper(status);
        return normalized.contains("UNKNOWN") || normalized.contains("UNSAT");
    }

    private static double observableDirectnessScore(String directness) {
        String normalized = upper(directness);
        if (normalized.contains("DIRECT_RETURN")) {
            return 1.0d;
        }
        if (normalized.contains("DIRECT_EXCEPTION")) {
            return 0.90d;
        }
        if (normalized.contains("DIRECT_FIELD")) {
            return 0.85d;
        }
        if (normalized.contains("SIMPLE_STATE")) {
            return 0.65d;
        }
        if (normalized.contains("MULTI_FIELD") || normalized.contains("AGGREGATE")) {
            return 0.35d;
        }
        if (normalized.contains("INDIRECT")) {
            return 0.15d;
        }
        return 0.0d;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static boolean isPublicLike(String visibility) {
        String normalized = upper(visibility);
        return "PUBLIC".equals(normalized) || normalized.isEmpty();
    }
}
