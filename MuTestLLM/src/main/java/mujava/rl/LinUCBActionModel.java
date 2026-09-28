package mujava.rl;

import org.json.JSONArray;
import org.json.JSONObject;

final class LinUCBActionModel {
    final double[][] a;
    final double[] b;

    LinUCBActionModel(int dimension, double lambda) {
        this.a = new double[dimension][dimension];
        this.b = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            this.a[i][i] = lambda;
        }
    }

    void update(double[] x, double reward, double weight) {
        double w = Math.max(0.0d, weight);
        for (int i = 0; i < x.length; i++) {
            b[i] += w * reward * x[i];
            for (int j = 0; j < x.length; j++) {
                a[i][j] += w * x[i] * x[j];
            }
        }
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        JSONArray aRows = new JSONArray();
        for (double[] row : a) {
            JSONArray r = new JSONArray();
            for (double v : row) {
                r.put(v);
            }
            aRows.put(r);
        }
        JSONArray bArr = new JSONArray();
        for (double v : b) {
            bArr.put(v);
        }
        obj.put("A", aRows);
        obj.put("b", bArr);
        return obj;
    }

    static LinUCBActionModel fromJson(JSONObject obj, int dimension, double lambda) {
        LinUCBActionModel model = new LinUCBActionModel(dimension, lambda);
        if (obj == null) {
            return model;
        }
        JSONArray aRows = obj.optJSONArray("A");
        if (aRows != null && aRows.length() == dimension) {
            for (int i = 0; i < dimension; i++) {
                JSONArray row = aRows.optJSONArray(i);
                if (row == null || row.length() != dimension) {
                    continue;
                }
                for (int j = 0; j < dimension; j++) {
                    model.a[i][j] = row.optDouble(j, model.a[i][j]);
                }
            }
        }
        JSONArray bArr = obj.optJSONArray("b");
        if (bArr != null && bArr.length() == dimension) {
            for (int i = 0; i < dimension; i++) {
                model.b[i] = bArr.optDouble(i, model.b[i]);
            }
        }
        return model;
    }
}
