package app.loopa

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import kotlin.math.ln

/**
 * Opt-in local diagnostics. No network API, no video IDs, no search strings.
 * Feature snapshots can contain personal preferences, so the journal is off by default.
 * Private app storage; bounded history; disabling diagnostics deletes the journal.
 */
internal object TrainingLab {
    private const val LIMIT = 10000
    private const val AGE_MS = 30L * 86400000L
    private const val PREF = "training_lab"
    private var prefs: SharedPreferences? = null
    private var helper: SQLiteOpenHelper? = null
    private val io by lazy { Handler(HandlerThread("training-lab").apply { start() }.looper) }
    @Volatile private var epoch = 0L
    val enabled get() = prefs?.getBoolean("enabled", false) == true
    val learning get() = prefs?.getBoolean("learning", true) != false

    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        helper = object : SQLiteOpenHelper(app, "training_lab.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, y REAL, w REAL, p REAL, video INTEGER, features TEXT)")
                db.execSQL("CREATE INDEX samples_at ON samples(at)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Future versions must supply non-destructive migrations.
                error("Unsupported training journal migration")
            }
        }
        io.post { if (!enabled) clearDb() else runCatching { prune(helper!!.writableDatabase) } }
    }

    @Synchronized fun setEnabled(on: Boolean) {
        epoch++
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
        if (!on) io.post { clearDb() }
    }

    fun setLearning(on: Boolean) {
        prefs?.edit()?.putBoolean("learning", on)?.apply()
    }

    @Synchronized fun clear() {
        epoch++
        io.post { clearDb() }
    }

    private fun clearDb() {
        runCatching {
            // Close and delete the database, including SQLite journal sidecars.
            helper?.close()
            val db = helper?.writableDatabase ?: return
            db.execSQL("PRAGMA secure_delete=ON")
            db.delete("samples", null, null)
            db.execSQL("VACUUM")
            helper?.close()
        }.onFailure { android.util.Log.w("Loopa", "Training journal deletion failed", it) }
    }

    private fun prune(db: SQLiteDatabase) {
        db.delete("samples", "at < ?", arrayOf((System.currentTimeMillis() - AGE_MS).toString()))
        db.execSQL("DELETE FROM samples WHERE id <= (SELECT id FROM samples ORDER BY id DESC LIMIT 1 OFFSET $LIMIT)")
    }

    private fun enqueue(kind: String, y: Float?, weight: Float?, p: Double?, video: Boolean?, features: String?) {
        if (!enabled) return
        val generation = epoch
        io.post {
            if (!enabled || generation != epoch) return@post
            runCatching {
                val db = helper?.writableDatabase ?: return@runCatching
                db.execSQL("INSERT INTO samples(at,kind,y,w,p,video,features) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(System.currentTimeMillis(), kind, y, weight, p, video?.let { if (it) 1 else 0 }, features))
                prune(db)
            }.onFailure { android.util.Log.w("Loopa", "Training journal write failed", it) }
        }
    }

    fun event(kind: String) = enqueue(kind, null, null, null, null, null)

    fun sample(kind: String, features: List<Pair<String, Float>>, y: Float, weight: Float, p: Double, video: Boolean) {
        if (!enabled || !weight.isFinite() || weight <= 0f || !y.isFinite() || y !in 0f..1f || !p.isFinite()) return
        if (features.any { !it.second.isFinite() || it.first.length > 512 }) return
        val rows = JSONArray()
        features.forEach { (name, x) -> rows.put(JSONArray().put(name).put(x.toDouble())) }
        enqueue(kind, y, weight, p, video, rows.toString())
    }

    fun report(callback: (String) -> Unit) {
        io.post {
            val result = runCatching {
                if (!enabled) return@runCatching "Local diagnostics disabled."
                val db = helper!!.writableDatabase
                prune(db)
                val out = StringBuilder("Local diagnostic journal (30 days / 10,000 rows)\n")
                db.rawQuery("SELECT kind,COUNT(*) FROM samples GROUP BY kind ORDER BY kind", null).use { c ->
                    while (c.moveToNext()) out.append(c.getString(0)).append(": ").append(c.getLong(1)).append('\n')
                }
                db.rawQuery("SELECT y,p,w FROM samples WHERE p IS NOT NULL", null).use { c ->
                    var n = 0; var loss = 0.0; var brier = 0.0
                    while (c.moveToNext()) {
                        val y = c.getDouble(0); val p = c.getDouble(1).coerceIn(1e-6, 1.0-1e-6)
                        loss -= y * ln(p) + (1-y) * ln(1-p)
                        brier += (p-y)*(p-y); n++
                    }
                    if (n > 0) out.append("Pre-update log loss: ").append("%.4f".format(java.util.Locale.ROOT, loss/n))
                        .append("\nBrier: ").append("%.4f".format(java.util.Locale.ROOT, brier/n))
                        .append("\nSamples: ").append(n)
                }
                out.toString()
            }.getOrElse { "Journal unavailable: " + it.javaClass.simpleName }
            callback(result)
        }
    }

    /** Replay a separate candidate against a chronological holdout. Never replaces the live model. */
    fun evaluate(callback: (String) -> Unit) {
        val generation = epoch
        io.post {
            val result = runCatching {
                if (!enabled || generation != epoch) return@runCatching "Local diagnostics disabled."
                val db = helper!!.writableDatabase
                prune(db)
                val rows = ArrayList<ReplaySample>()
                db.rawQuery("SELECT features,y,w,video FROM samples WHERE features IS NOT NULL ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val a = JSONArray(c.getString(0))
                        val f = List(a.length()) { i -> a.getJSONArray(i).let { it.getString(0) to it.getDouble(1).toFloat() } }
                        rows += ReplaySample(f, c.getFloat(1), c.getFloat(2), c.getInt(3) != 0)
                    }
                }
                val metrics = ReplayEvaluator.run(rows) { enabled && generation == epoch }
                    ?: return@runCatching "Need at least 100 recorded samples; validation must contain both labels, or evaluation was cancelled."
                "Diagnostic replay only (not deployed)\nTrain: ${metrics.train} | Test: ${metrics.test}\n" +
                    "Candidate log loss: %.4f\nConstant baseline: %.4f\nCandidate Brier: %.4f".format(java.util.Locale.ROOT, metrics.loss, metrics.baseline, metrics.brier)
            }.getOrElse { "Replay unavailable: " + it.javaClass.simpleName }
            callback(result)
        }
    }
}
