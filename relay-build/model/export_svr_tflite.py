#!/usr/bin/env python3
"""
HeatWatch — export the production Mixed-regime SVR to TFLite fp32, in the
form both this build's web (LiteRT.js) and Android (org.tensorflow:tensorflow-lite)
clients can actually load.

Adapted from ../../relay-build/model/export_svr_tflite.py with one change:
a FIXED batch=1 input signature, not [None, n_features]. A dynamic batch dim
compiles to shape [-1, 5] in the .tflite flatbuffer; the Python TFLite
interpreter and Android's tensorflow-lite both silently coerce a concrete
[1, 5] input against that, but LiteRT.js's WASM runtime does a strict
shape-equality check and throws "TensorBuffer ranked tensor type Float32[1, 5]
does not match expected ranked tensor type Float32[-1, 5]" instead of
coercing — found live, in an actual browser, the hard way. Every consumer of
this model scores one reading at a time, so there was never a reason for the
dynamic dim.

Run in the TensorFlow venv (e.g. `exporting to TFLite/.venv`):

    python3 export_svr_tflite.py

Inputs : svr_heatwatch_params.json (copied from ../../relay-build/model/)
         golden_vectors.json        (copied from the same place — parity check)
Outputs: svr_heatwatch_fp32.tflite
         svr_heatwatch_tf/ (SavedModel)

⚠ Known precision caveat (unrelated to the batch-shape fix above): this model
has 6,466 support vectors and C≈623.7, so its fp32 decision-function sum has
~3.75M of cumulative |dual_coef| mass to hold precision through in float32.
Measured result: max|diff| vs sklearn = 2.5e-2 on the 20 golden vectors,
~250x the design spec's <1e-4 target. See ../README.md for the full writeup
and the contrast against the Real-only model this build shipped previously
(which — the opposite finding — converts exactly).
"""
import json, os, shutil, numpy as np, tensorflow as tf

HERE = os.path.dirname(os.path.abspath(__file__))
P = json.load(open(os.path.join(HERE, "svr_heatwatch_params.json")))

mean  = np.asarray(P["scaler"]["mean"],  np.float32)
scale = np.asarray(P["scaler"]["scale"], np.float32)
SV    = np.asarray(P["svr"]["support_vectors"], np.float32)
dcoef = np.asarray(P["svr"]["dual_coef"], np.float32)
gamma = np.float32(P["svr"]["gamma"])
b     = np.float32(P["svr"]["intercept"])
n_feat = len(P["features"])

print(f"Loaded {'PREVIEW' if P.get('preview') else 'PRODUCTION'} model: "
      f"n_SV={SV.shape[0]}, features={P['features']}")


class SVRModule(tf.Module):
    """Folds StandardScaler + RBF SVR decision function into one graph."""
    def __init__(self):
        super().__init__()
        self.mean  = tf.constant(mean)
        self.scale = tf.constant(scale)
        self.SV    = tf.constant(SV)
        self.dcoef = tf.constant(dcoef.reshape(-1, 1))
        self.gamma = tf.constant(gamma)
        self.b     = tf.constant(b)

    @tf.function(input_signature=[tf.TensorSpec([1, n_feat], tf.float32)])  # fixed batch=1, see module docstring
    def __call__(self, x):
        z = (x - self.mean) / self.scale
        z2  = tf.reduce_sum(z * z, axis=1, keepdims=True)
        sv2 = tf.reduce_sum(self.SV * self.SV, axis=1)
        cross = tf.matmul(z, self.SV, transpose_b=True)
        d2 = z2 - 2.0 * cross + sv2
        K  = tf.exp(-self.gamma * d2)
        return tf.matmul(K, self.dcoef) + self.b


mod = SVRModule()

sm_dir = os.path.join(HERE, "svr_heatwatch_tf")
if os.path.exists(sm_dir):
    shutil.rmtree(sm_dir)
tf.saved_model.save(mod, sm_dir)

conv = tf.lite.TFLiteConverter.from_saved_model(sm_dir)
conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
conv.optimizations = []
tflite = conv.convert()
out = os.path.join(HERE, "svr_heatwatch_fp32.tflite")
open(out, "wb").write(tflite)
print(f"wrote {out}  ({len(tflite)/1024:.1f} KB)")

gv_path = os.path.join(HERE, "golden_vectors.json")
if os.path.exists(gv_path):
    gv = json.load(open(gv_path))["cases"]
    X = np.array([c["x"] for c in gv], np.float32)
    y_ref = np.array([c["y_sklearn"] for c in gv], np.float32)
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]; outd = interp.get_output_details()[0]
    print(f"input shape_signature: {inp['shape_signature']}  (expect [1 {n_feat}], not [-1 {n_feat}])")
    y_tfl = []
    for row in X:
        interp.set_tensor(inp["index"], row[None, :])
        interp.invoke()
        y_tfl.append(float(interp.get_tensor(outd["index"])[0, 0]))
    y_tfl = np.array(y_tfl, np.float32)
    print(f"TFLite vs sklearn  max|diff| = {np.max(np.abs(y_tfl - y_ref)):.3e}  (expect < 1e-4)")
else:
    print("golden_vectors.json not found — skipped parity gate.")
