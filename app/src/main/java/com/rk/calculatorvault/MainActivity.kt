package com.rk.calculatorvault

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val isTrash: Boolean
)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rk_vault_secure", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private var expression = ""
    private var lastResult = ""
    private var justEvaluated = false
    private var lastEqualsExpression: String? = null
    private var lastEqualsResult: String? = null

    private var sessionKey: SecretKeySpec? = null
    private var vaultOpen = false
    private var showingTrash = false
    private var selectedCategory = "Semua"
    private var searchTerm = ""
    private var externalActionInProgress = false
    private var pendingExportItem: VaultItem? = null
    private var activePreviewDialog: Dialog? = null
    private var activePreviewVideo: VideoView? = null

    private lateinit var page: LinearLayout
    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView
    private lateinit var vaultListContainer: LinearLayout
    private lateinit var vaultCountView: TextView
    private lateinit var searchInput: EditText

    private val colorBackground = Color.rgb(243, 246, 252)
    private val colorNavy = Color.rgb(31, 40, 57)
    private val colorKey = Color.rgb(50, 60, 80)
    private val colorMutedKey = Color.rgb(66, 77, 99)
    private val colorBlue = Color.rgb(38, 119, 245)
    private val colorWhite = Color.WHITE

    companion object {
        private const val REQ_IMPORT = 4101
        private const val REQ_EXPORT = 4102
        private const val PBKDF_ITERATIONS = 100_000
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = colorBackground
        window.navigationBarColor = colorBackground
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        deletePreviewCache()
        renderCalculator()
    }

    override fun onStop() {
        super.onStop()
        if (vaultOpen && !externalActionInProgress && !isChangingConfigurations) {
            lockVaultToCalculator()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    @Deprecated("Deprecated by Android, retained for compatibility with document picker results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        externalActionInProgress = false
        if (resultCode != RESULT_OK || data == null) {
            pendingExportItem = null
            if (vaultOpen) renderVault()
            return
        }

        if (requestCode == REQ_IMPORT) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importUris(uris.distinct()) else if (vaultOpen) renderVault()
        } else if (requestCode == REQ_EXPORT) {
            val item = pendingExportItem
            pendingExportItem = null
            val target = data.data
            if (item != null && target != null) exportItem(item, target)
        }
    }

    override fun onBackPressed() {
        if (vaultOpen) {
            lockVaultToCalculator()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun makeText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        this.gravity = gravity
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun makeButton(
        label: String,
        background: Int,
        textColor: Int = Color.WHITE,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        this.background = rounded(background, 15)
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun hasPin(): Boolean = prefs.contains("wrapped_master") && prefs.contains("pin_salt")

    private fun renderCalculator() {
        vaultOpen = false
        sessionKey = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(12))
        }
        val mark = TextView(this).apply {
            text = "＋"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(colorBlue, 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        titleRow.addView(mark)
        val appTitle = makeText("Kalkulator", 20f, colorNavy, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }
        titleRow.addView(appTitle)
        page.addView(titleRow)

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = rounded(colorNavy, 28)
        }
        expressionView = makeText("0", 31f, colorWhite, false, Gravity.END).apply {
            maxLines = 2
            setHorizontallyScrolling(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        resultView = makeText("", 20f, Color.rgb(177, 197, 232), false, Gravity.END).apply {
            setPadding(0, dp(8), 0, 0)
            maxLines = 1
        }
        display.addView(expressionView, LinearLayout.LayoutParams(-1, 0, 1f))
        display.addView(resultView)
        page.addView(display, LinearLayout.LayoutParams(-1, 0, 0.9f).apply {
            bottomMargin = dp(14)
        })

        val keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colorNavy, 28)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val keyRows = listOf(
            listOf("AC", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )
        keyRows.forEach { labels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEach { label ->
                val isOperator = label in listOf("%", "÷", "×", "−", "+", "±", "⌫", "AC")
                val bg = when {
                    label == "=" -> colorBlue
                    label == "AC" -> Color.rgb(85, 100, 126)
                    isOperator -> colorMutedKey
                    else -> colorKey
                }
                val fg = if (label == "AC") Color.WHITE else Color.WHITE
                val key = makeButton(label, bg, fg) { onCalculatorKey(label) }.apply {
                    textSize = if (label == "AC" || label == "⌫") 17f else 23f
                    minHeight = dp(55)
                }
                row.addView(key, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            keyboard.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        page.addView(keyboard, LinearLayout.LayoutParams(-1, 0, 1.45f))
        setContentView(page)
        updateCalculatorDisplay()
    }

    private fun updateCalculatorDisplay() {
        if (!::expressionView.isInitialized || !::resultView.isInitialized) return
        expressionView.text = if (expression.isBlank()) "0" else expression.replace("-", "−")
        resultView.text = if (lastResult.isBlank()) "" else "= $lastResult"
    }

    private fun onCalculatorKey(label: String) {
        when (label) {
            "AC" -> {
                expression = ""
                lastResult = ""
                justEvaluated = false
                lastEqualsExpression = null
                lastEqualsResult = null
            }
            "⌫" -> {
                if (justEvaluated) {
                    expression = lastResult
                    justEvaluated = false
                }
                if (expression.isNotEmpty()) expression = expression.dropLast(1)
                lastEqualsExpression = null
            }
            "=" -> handleEquals()
            "±" -> toggleSign()
            "+", "−", "×", "÷", "%" -> {
                if (justEvaluated) {
                    expression = lastResult
                    justEvaluated = false
                }
                val operator = if (label == "−") "-" else label
                if (operator == "%") {
                    if (expression.isNotEmpty() && expression.last().isDigit()) expression += "%"
                } else if (expression.isNotEmpty()) {
                    if (expression.last().toString() in listOf("+", "-", "×", "÷")) {
                        expression = expression.dropLast(1) + operator
                    } else expression += operator
                } else if (operator == "-") expression = "-"
                lastResult = ""
                lastEqualsExpression = null
            }
            "." -> {
                if (justEvaluated) {
                    expression = ""
                    justEvaluated = false
                }
                val lastNumber = expression.substringAfterLastAny('+', '-', '×', '÷', '%')
                if (!lastNumber.contains('.')) expression += if (lastNumber.isEmpty()) "0." else "."
                lastResult = ""
                lastEqualsExpression = null
            }
            else -> {
                if (justEvaluated) {
                    expression = ""
                    justEvaluated = false
                }
                expression += label
                lastResult = ""
                lastEqualsExpression = null
            }
        }
        updateCalculatorDisplay()
    }

    private fun String.substringAfterLastAny(vararg chars: Char): String {
        val index = chars.map { lastIndexOf(it) }.maxOrNull() ?: -1
        return substring(index + 1)
    }

    private fun toggleSign() {
        if (expression.isBlank()) {
            expression = "-"
        } else {
            val operatorIndex = maxOf(
                expression.lastIndexOf('+'),
                expression.lastIndexOf('-').let { if (it == 0) -1 else it },
                expression.lastIndexOf('×'),
                expression.lastIndexOf('÷')
            )
            val start = operatorIndex + 1
            val tail = expression.substring(start)
            expression = if (tail.startsWith("-")) {
                expression.substring(0, start) + tail.drop(1)
            } else {
                expression.substring(0, start) + "-" + tail
            }
        }
        lastResult = ""
        lastEqualsExpression = null
        justEvaluated = false
    }

    private fun handleEquals() {
        if (expression.isBlank()) return
        val input = expression
        val result = try {
            formatNumber(ExpressionParser(input).parse())
        } catch (_: Exception) {
            lastResult = "Error"
            justEvaluated = true
            updateCalculatorDisplay()
            return
        }

        val pinCandidate = if (input.matches(Regex("[0-9]{4,12}"))) input else result
        val isRepeatedEquals = lastEqualsExpression == input && lastEqualsResult == result
        if (isRepeatedEquals && pinCandidate.matches(Regex("[0-9]{4,12}"))) {
            if (!hasPin()) {
                createVaultPin(pinCandidate)
                lastEqualsExpression = null
                lastEqualsResult = null
                return
            }
            val unlocked = tryUnlock(pinCandidate)
            if (unlocked) {
                lastEqualsExpression = null
                lastEqualsResult = null
                openVault()
                return
            }
            // A wrong value simply behaves like a normal calculator result.
            lastEqualsExpression = null
            lastEqualsResult = null
            lastResult = result
            justEvaluated = true
            updateCalculatorDisplay()
            return
        }

        lastEqualsExpression = input
        lastEqualsResult = result
        lastResult = result
        justEvaluated = true
        updateCalculatorDisplay()
    }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) throw IllegalArgumentException("Invalid result")
        if (kotlin.math.abs(value) > 9.0e15) return String.format(Locale.US, "%.8g", value)
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    private fun createVaultPin(pin: String) {
        try {
            val master = ByteArray(32).also { random.nextBytes(it) }
            storeWrappedMaster(pin, master)
            sessionKey = SecretKeySpec(master, "AES")
            makeVaultDirectories()
            expression = ""
            lastResult = ""
            justEvaluated = false
            openVault()
        } catch (_: Exception) {
            lastResult = "Error"
            updateCalculatorDisplay()
        }
    }

    private fun tryUnlock(pin: String): Boolean {
        return try {
            val master = unwrapMaster(pin) ?: return false
            sessionKey = SecretKeySpec(master, "AES")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun derivePinKey(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun storeWrappedMaster(pin: String, master: ByteArray) {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val wrappingKey = SecretKeySpec(derivePinKey(pin, salt), "AES")
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("RK-CALCULATOR-VAULT-MASTER-v1".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(master)
        val packed = iv + encrypted
        val saved = prefs.edit()
            .putString("pin_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString("wrapped_master", android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP))
            .commit()
        if (!saved) throw IllegalStateException("Tidak bisa menyimpan pengaturan keamanan")
    }

    private fun unwrapMaster(pin: String): ByteArray? {
        val saltString = prefs.getString("pin_salt", null) ?: return null
        val packedString = prefs.getString("wrapped_master", null) ?: return null
        val salt = android.util.Base64.decode(saltString, android.util.Base64.NO_WRAP)
        val packed = android.util.Base64.decode(packedString, android.util.Base64.NO_WRAP)
        if (packed.size <= GCM_IV_BYTES) return null
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val wrappingKey = SecretKeySpec(derivePinKey(pin, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("RK-CALCULATOR-VAULT-MASTER-v1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }

    private fun openVault() {
        if (sessionKey == null) return
        vaultOpen = true
        showingTrash = false
        selectedCategory = "Semua"
        searchTerm = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        renderVault()
    }

    private fun lockVaultToCalculator() {
        vaultOpen = false
        sessionKey = null
        showingTrash = false
        searchTerm = ""
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { activePreviewVideo?.stopPlayback() } catch (_: Exception) { }
        activePreviewVideo = null
        try { activePreviewDialog?.dismiss() } catch (_: Exception) { }
        activePreviewDialog = null
        deletePreviewCache()
        renderCalculator()
    }

    private fun makeVaultDirectories() {
        listOf(activeDataDir(), activeMetaDir(), trashDataDir(), trashMetaDir(), previewDir()).forEach { it.mkdirs() }
    }

    private fun activeDataDir() = File(filesDir, "secure/data")
    private fun activeMetaDir() = File(filesDir, "secure/index")
    private fun trashDataDir() = File(filesDir, "secure/trash/data")
    private fun trashMetaDir() = File(filesDir, "secure/trash/index")
    private fun previewDir() = File(cacheDir, "private_preview")

    private fun renderVault() {
        val key = sessionKey ?: run { renderCalculator(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        makeVaultDirectories()
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(makeText(if (showingTrash) "Sampah" else "Ruang Pribadi", 23f, colorNavy, true))
        titles.addView(makeText("Tersimpan lokal • terenkripsi", 12f, Color.rgb(100, 113, 137)))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makeButton("Kalkulator", colorNavy) { lockVaultToCalculator() })
        page.addView(header)

        val actions1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(5))
        }
        if (showingTrash) {
            actions1.addView(makeButton("← File Saya", colorMutedKey) {
                showingTrash = false
                selectedCategory = "Semua"
                searchTerm = ""
                renderVault()
            }, weightedButtonParams())
            actions1.addView(makeButton("Kosongkan", Color.rgb(183, 63, 74)) { confirmEmptyTrash() }, weightedButtonParams())
        } else {
            actions1.addView(makeButton("＋ Impor", colorBlue) { startImport() }, weightedButtonParams())
            actions1.addView(makeButton("Sampah", colorMutedKey) {
                showingTrash = true
                selectedCategory = "Semua"
                searchTerm = ""
                renderVault()
            }, weightedButtonParams())
        }
        page.addView(actions1)

        val actions2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        actions2.addView(makeButton("Pengaturan", colorMutedKey) { showSettings() }, weightedButtonParams())
        if (!showingTrash) {
            actions2.addView(makeButton("Sembunyikan Ikon", colorMutedKey) { showIconOptions() }, weightedButtonParams())
        } else {
            actions2.addView(makeButton("Pulihkan Semua", colorMutedKey) { restoreAllTrash() }, weightedButtonParams())
        }
        page.addView(actions2)

        if (!showingTrash) {
            searchInput = EditText(this).apply {
                hint = "Cari file pribadi"
                textSize = 14f
                setSingleLine(true)
                setTextColor(colorNavy)
                setHintTextColor(Color.rgb(130, 141, 162))
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(Color.WHITE, 14)
                setText(searchTerm)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchTerm = s?.toString().orEmpty()
                        refreshVaultItems(key)
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            page.addView(searchInput, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })

            val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Semua", "Foto", "Video", "File").forEach { category ->
                val active = selectedCategory == category
                filters.addView(makeButton(category, if (active) colorBlue else Color.WHITE, if (active) Color.WHITE else colorNavy) {
                    selectedCategory = category
                    renderVault()
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), 0, dp(3), dp(8)) })
            }
            page.addView(filters)
        }

        vaultCountView = makeText("Memuat file…", 12f, Color.rgb(100, 113, 137))
        vaultCountView.setPadding(dp(2), dp(2), 0, dp(7))
        page.addView(vaultCountView)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        vaultListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        scroll.addView(vaultListContainer)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        refreshVaultItems(key)
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        setMargins(dp(3), dp(2), dp(3), dp(2))
    }

    private fun refreshVaultItems(key: SecretKeySpec) {
        val trashSnapshot = showingTrash
        val categorySnapshot = selectedCategory
        val querySnapshot = searchTerm
        io.execute {
            val items = loadItems(trashSnapshot, key)
            mainHandler.post {
                if (!vaultOpen || sessionKey?.encoded?.contentEquals(key.encoded) != true) return@post
                if (showingTrash != trashSnapshot || selectedCategory != categorySnapshot || searchTerm != querySnapshot) return@post
                if (!::vaultListContainer.isInitialized) return@post
                vaultListContainer.removeAllViews()
                val filtered = items.filter { item ->
                    val categoryMatches = when (categorySnapshot) {
                        "Foto" -> item.mime.startsWith("image/")
                        "Video" -> item.mime.startsWith("video/")
                        "File" -> !item.mime.startsWith("image/") && !item.mime.startsWith("video/")
                        else -> true
                    }
                    categoryMatches && item.name.contains(querySnapshot, ignoreCase = true)
                }.sortedByDescending { it.modified }

                val totalBytes = items.sumOf { it.size.coerceAtLeast(0) }
                vaultCountView.text = "${filtered.size} item • ${humanSize(totalBytes)}"
                if (filtered.isEmpty()) {
                    val empty = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = rounded(Color.WHITE, 22)
                        setPadding(dp(20), dp(30), dp(20), dp(30))
                    }
                    empty.addView(makeText(if (showingTrash) "Sampah kosong" else "Belum ada file", 18f, colorNavy, true, Gravity.CENTER))
                    empty.addView(makeText(if (showingTrash) "File yang dihapus akan muncul di sini." else "Tekan Impor untuk menyimpan foto, video, atau file.", 13f, Color.rgb(100, 113, 137), false, Gravity.CENTER).apply {
                        setPadding(0, dp(8), 0, 0)
                    })
                    vaultListContainer.addView(empty)
                } else {
                    filtered.forEach { item ->
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = rounded(Color.WHITE, 18)
                            setPadding(dp(12), dp(13), dp(12), dp(13))
                            setOnClickListener { showItemActions(item) }
                        }
                        val iconText = when {
                            item.mime.startsWith("image/") -> "▧"
                            item.mime.startsWith("video/") -> "▶"
                            else -> "▤"
                        }
                        val icon = makeText(iconText, 24f, colorBlue, true, Gravity.CENTER).apply {
                            background = rounded(Color.rgb(235, 241, 255), 12)
                        }
                        card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
                        val details = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(11), 0, dp(4), 0)
                        }
                        details.addView(makeText(item.name, 14f, colorNavy, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                        details.addView(makeText("${humanSize(item.size)} • ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.modified))}", 11f, Color.rgb(111, 124, 147)).apply {
                            setPadding(0, dp(4), 0, 0)
                        })
                        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        card.addView(makeText("›", 24f, Color.rgb(130, 142, 163), false, Gravity.CENTER))
                        vaultListContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                    }
                }
            }
        }
    }

    private fun loadItems(trash: Boolean, key: SecretKeySpec): List<VaultItem> {
        val metaDir = if (trash) trashMetaDir() else activeMetaDir()
        if (!metaDir.exists()) return emptyList()
        val result = mutableListOf<VaultItem>()
        metaDir.listFiles()?.filter { it.isFile && it.extension == "meta" }?.forEach { metaFile ->
            try {
                val id = metaFile.nameWithoutExtension
                val metadata = decryptMetadata(metaFile, key)
                val dataFile = File(if (trash) trashDataDir() else activeDataDir(), "$id.bin")
                if (dataFile.exists()) result.add(VaultItem(id, metadata.first, metadata.second, metadata.third, metadata.fourth, trash))
            } catch (_: Exception) {
                // Corrupt/incomplete metadata is skipped rather than crashing the vault UI.
            }
        }
        return result
    }

    private fun encryptMetadata(file: File, item: VaultItem, key: SecretKeySpec) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeUTF(item.name.take(600))
            data.writeUTF(item.mime.take(250))
            data.writeLong(item.size)
            data.writeLong(item.modified)
        }
        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(item.id.toByteArray(Charsets.UTF_8))
        val cipherText = cipher.doFinal(bytes.toByteArray())
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(iv + cipherText) }
    }

    private fun decryptMetadata(file: File, key: SecretKeySpec): Quad<String, String, Long, Long> {
        val all = file.readBytes()
        if (all.size <= GCM_IV_BYTES) throw IllegalStateException("Metadata rusak")
        val id = file.nameWithoutExtension
        val iv = all.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = all.copyOfRange(GCM_IV_BYTES, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(encrypted)
        DataInputStream(ByteArrayInputStream(plain)).use { data ->
            return Quad(data.readUTF(), data.readUTF(), data.readLong(), data.readLong())
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            externalActionInProgress = true
            startActivityForResult(intent, REQ_IMPORT)
        } catch (_: Exception) {
            externalActionInProgress = false
            toast("Pemilih file tidak tersedia di perangkat ini.")
        }
    }

    private fun importUris(uris: List<Uri>) {
        val key = sessionKey ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Menyimpan file")
            .setMessage("File sedang dienkripsi ke penyimpanan lokal…")
            .setCancelable(false)
            .create()
        if (!isFinishing) progressDialog.show()
        io.execute {
            var success = 0
            var failed = 0
            uris.forEach { uri ->
                try {
                    if (importUri(uri, key)) success++ else failed++
                } catch (_: Exception) {
                    failed++
                }
            }
            mainHandler.post {
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (vaultOpen) renderVault()
                if (failed > 0) toast("Selesai: $success berhasil, $failed gagal.")
                else toast("${success} file berhasil disimpan.")
            }
        }
    }

    private fun importUri(uri: Uri, key: SecretKeySpec): Boolean {
        val name = queryDisplayName(uri) ?: "File_${System.currentTimeMillis()}"
        val providedMime = contentResolver.getType(uri)
        val inferredMime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.US))
        val mime = if (providedMime.isNullOrBlank() || providedMime == "application/octet-stream") {
            inferredMime ?: providedMime ?: "application/octet-stream"
        } else providedMime
        val id = UUID.randomUUID().toString()
        val dest = File(activeDataDir().apply { mkdirs() }, "$id.bin")
        var count = 0L
        try {
            val input = contentResolver.openInputStream(uri) ?: return false
            input.use { source ->
                val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                val fileOutput = FileOutputStream(dest)
                fileOutput.write(iv)
                CipherOutputStream(fileOutput, cipher).use { encrypted ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        encrypted.write(buffer, 0, read)
                        count += read
                    }
                }
            }
            val item = VaultItem(id, name, mime, count, System.currentTimeMillis(), false)
            encryptMetadata(File(activeMetaDir().apply { mkdirs() }, "$id.meta"), item, key)
            return true
        } catch (e: Exception) {
            dest.delete()
            File(activeMetaDir(), "$id.meta").delete()
            throw e
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        } catch (_: Exception) { null }
    }

    private fun showItemActions(item: VaultItem) {
        val options = if (item.isTrash) {
            arrayOf("Pratinjau", "Ekspor ke HP", "Pulihkan", "Hapus permanen", "Batal")
        } else {
            arrayOf("Pratinjau", "Ekspor ke HP", "Pindahkan ke Sampah", "Batal")
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Pratinjau" -> previewItem(item)
                    "Ekspor ke HP" -> startExport(item)
                    "Pindahkan ke Sampah" -> moveToTrash(item)
                    "Pulihkan" -> restoreFromTrash(item)
                    "Hapus permanen" -> confirmPermanentDelete(item)
                }
            }
            .show()
    }

    private fun startExport(item: VaultItem) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, item.name)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            pendingExportItem = item
            externalActionInProgress = true
            startActivityForResult(intent, REQ_EXPORT)
        } catch (_: Exception) {
            pendingExportItem = null
            externalActionInProgress = false
            toast("Tidak bisa membuka lokasi ekspor.")
        }
    }

    private fun exportItem(item: VaultItem, uri: Uri) {
        val key = sessionKey ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Mengekspor file")
            .setMessage("File sedang dipulihkan ke lokasi yang kamu pilih…")
            .setCancelable(false)
            .create()
        if (!isFinishing) progressDialog.show()
        io.execute {
            var ok = false
            try {
                val sourceFile = File(if (item.isTrash) trashDataDir() else activeDataDir(), "${item.id}.bin")
                FileInputStream(sourceFile).use { source ->
                    val iv = ByteArray(GCM_IV_BYTES)
                    var offset = 0
                    while (offset < iv.size) {
                        val n = source.read(iv, offset, iv.size - offset)
                        if (n < 0) throw IllegalStateException("File tidak lengkap")
                        offset += n
                    }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.updateAAD(item.id.toByteArray(Charsets.UTF_8))
                    val encrypted = CipherInputStream(source, cipher)
                    val output = contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Lokasi ekspor tidak bisa ditulis")
                    output.use { target ->
                        encrypted.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                target.write(buffer, 0, read)
                            }
                        }
                    }
                }
                ok = true
            } catch (_: Exception) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            mainHandler.post {
                if (progressDialog.isShowing) progressDialog.dismiss()
                toast(if (ok) "File berhasil diekspor." else "Ekspor gagal. Coba lokasi lain atau periksa ruang penyimpanan.")
            }
        }
    }

    private fun moveToTrash(item: VaultItem) {
        val key = sessionKey ?: return
        try {
            movePair(item.id, activeDataDir(), activeMetaDir(), trashDataDir(), trashMetaDir())
            renderVault()
            toast("File dipindahkan ke Sampah.")
        } catch (_: Exception) {
            toast("File tidak berhasil dipindahkan.")
        }
    }

    private fun restoreFromTrash(item: VaultItem) {
        try {
            movePair(item.id, trashDataDir(), trashMetaDir(), activeDataDir(), activeMetaDir())
            renderVault()
            toast("File dipulihkan.")
        } catch (_: Exception) {
            toast("File tidak berhasil dipulihkan.")
        }
    }

    private fun movePair(id: String, fromData: File, fromMeta: File, toData: File, toMeta: File) {
        toData.mkdirs(); toMeta.mkdirs()
        val sourceData = File(fromData, "$id.bin")
        val sourceMeta = File(fromMeta, "$id.meta")
        val destData = File(toData, "$id.bin")
        val destMeta = File(toMeta, "$id.meta")
        if (!sourceData.exists() || !sourceMeta.exists()) throw IllegalStateException("File tidak ditemukan")
        if (!sourceData.renameTo(destData)) {
            sourceData.copyTo(destData, overwrite = true)
            sourceData.delete()
        }
        if (!sourceMeta.renameTo(destMeta)) {
            try {
                sourceMeta.copyTo(destMeta, overwrite = true)
                sourceMeta.delete()
            } catch (e: Exception) {
                destData.delete()
                throw e
            }
        }
    }

    private fun confirmPermanentDelete(item: VaultItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus permanen?")
            .setMessage("${item.name} akan dihapus dari penyimpanan aplikasi dan tidak bisa dipulihkan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                File(trashDataDir(), "${item.id}.bin").delete()
                File(trashMetaDir(), "${item.id}.meta").delete()
                renderVault()
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        AlertDialog.Builder(this)
            .setTitle("Kosongkan Sampah?")
            .setMessage("Semua file di Sampah akan dihapus permanen.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Kosongkan") { _, _ ->
                trashDataDir().listFiles()?.forEach { it.delete() }
                trashMetaDir().listFiles()?.forEach { it.delete() }
                renderVault()
            }
            .show()
    }

    private fun restoreAllTrash() {
        val items = loadItems(true, sessionKey ?: return)
        items.forEach { item ->
            try { movePair(item.id, trashDataDir(), trashMetaDir(), activeDataDir(), activeMetaDir()) } catch (_: Exception) { }
        }
        showingTrash = false
        renderVault()
    }

    private fun showSettings() {
        val options = arrayOf("Ganti PIN", "Info keamanan", "Batal")
        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Ganti PIN" -> showChangePinDialog()
                    "Info keamanan" -> showSecurityInfo()
                }
            }
            .show()
    }

    private fun showChangePinDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(4))
        }
        fun pinField(hintText: String): EditText = EditText(this).apply {
            hint = hintText
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            setSingleLine(true)
        }
        val oldPin = pinField("PIN saat ini")
        val newPin = pinField("PIN baru (4–12 angka)")
        val confirmPin = pinField("Ulangi PIN baru")
        content.addView(oldPin)
        content.addView(newPin)
        content.addView(confirmPin)
        AlertDialog.Builder(this)
            .setTitle("Ganti PIN brankas")
            .setView(content)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val old = oldPin.text.toString()
                        val newer = newPin.text.toString()
                        val confirm = confirmPin.text.toString()
                        if (newer.length !in 4..12 || !newer.all { it.isDigit() }) {
                            toast("PIN baru harus terdiri dari 4–12 angka.")
                            return@setOnClickListener
                        }
                        if (newer != confirm) {
                            toast("Ulangan PIN belum sama.")
                            return@setOnClickListener
                        }
                        try {
                            val master = unwrapMaster(old)
                            if (master == null) {
                                toast("PIN saat ini salah.")
                                return@setOnClickListener
                            }
                            storeWrappedMaster(newer, master)
                            sessionKey = SecretKeySpec(master, "AES")
                            dialog.dismiss()
                            toast("PIN berhasil diganti.")
                        } catch (_: Exception) {
                            toast("PIN saat ini salah atau brankas tidak dapat dibuka.")
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showSecurityInfo() {
        AlertDialog.Builder(this)
            .setTitle("Keamanan lokal")
            .setMessage("File dan nama file disimpan di direktori privat aplikasi lalu dienkripsi AES-GCM. Aplikasi tidak memakai akun, server, atau izin internet. Ekspor membuat salinan biasa di lokasi yang kamu pilih. Jika PIN terlupa atau data aplikasi dihapus/uninstall, file terenkripsi tidak bisa dipulihkan.")
            .setPositiveButton("Mengerti", null)
            .show()
    }

    private fun showIconOptions() {
        val component = ComponentName(this, "$packageName.LauncherAlias")
        val state = packageManager.getComponentEnabledSetting(component)
        val iconVisible = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        if (!iconVisible) {
            AlertDialog.Builder(this)
                .setTitle("Ikon Kalkulator")
                .setMessage("Tampilkan kembali ikon di layar aplikasi?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Tampilkan") { _, _ ->
                    packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    toast("Ikon Kalkulator ditampilkan kembali.")
                    renderVault()
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sembunyikan ikon")
            .setMessage("Sebelum menyembunyikan ikon, tambahkan ubin cepat bernama Kalkulator melalui panel Setelan Cepat. Untuk pemulihan alternatif, buka rkcalc://restore di browser. Ikon hanya hilang dari peluncur; aplikasi masih dapat terlihat di Pengaturan Android.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Sembunyikan") { _, _ ->
                packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                toast("Ikon peluncur disembunyikan. Jalur pemulihan tetap tersedia.")
            }
            .show()
    }

    private fun previewItem(item: VaultItem) {
        if (!item.mime.startsWith("image/") && !item.mime.startsWith("video/")) {
            AlertDialog.Builder(this)
                .setTitle("Pratinjau file")
                .setMessage("Tipe file ini tidak dapat ditampilkan langsung. Pilih Ekspor ke HP untuk membukanya dengan aplikasi yang sesuai.")
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }
        val dialog = Dialog(this)
        activePreviewDialog = dialog
        activePreviewVideo = null
        val holder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            minimumWidth = resources.displayMetrics.widthPixels - dp(28)
            minimumHeight = dp(320)
        }
        val loading = ProgressBar(this).apply {
            isIndeterminate = true
        }
        holder.addView(loading, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
        dialog.setContentView(holder)
        dialog.window?.setBackgroundDrawable(rounded(Color.BLACK, 20))
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(28), dp(480))
        val key = sessionKey ?: run { dialog.dismiss(); return }
        io.execute {
            var temp: File? = null
            try {
                val tempFile = File(previewDir().apply { mkdirs() }, "preview_${UUID.randomUUID()}.${extensionFor(item)}")
                temp = tempFile
                decryptToFile(item, key, tempFile)
                mainHandler.post {
                    if (!dialog.isShowing || !vaultOpen) {
                        tempFile.delete()
                        return@post
                    }
                    holder.removeAllViews()
                    if (item.mime.startsWith("image/")) {
                        val bitmap = decodeSampledBitmap(tempFile, 1600, 1600)
                        val image = ImageView(this).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                        }
                        holder.addView(image, FrameLayout.LayoutParams(-1, -1))
                    } else {
                        val video = VideoView(this)
                        activePreviewVideo = video
                        val controls = MediaController(this)
                        controls.setAnchorView(video)
                        video.setMediaController(controls)
                        video.setVideoPath(tempFile.absolutePath)
                        holder.addView(video, FrameLayout.LayoutParams(-1, -1))
                        video.setOnPreparedListener { it.isLooping = false; video.start() }
                    }
                    dialog.setOnDismissListener {
                        try { activePreviewVideo?.stopPlayback() } catch (_: Exception) { }
                        activePreviewVideo = null
                        activePreviewDialog = null
                        try { tempFile.delete() } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
                temp?.delete()
                mainHandler.post {
                    if (dialog.isShowing) dialog.dismiss()
                    activePreviewDialog = null
                    activePreviewVideo = null
                    toast("Pratinjau gagal dibuka. Coba ekspor file.")
                }
            }
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outHeight / sample > reqHeight || bounds.outWidth / sample > reqWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun extensionFor(item: VaultItem): String {
        val nameExt = item.name.substringAfterLast('.', "").lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)
        if (nameExt.isNotBlank()) return nameExt
        return when {
            item.mime == "image/jpeg" -> "jpg"
            item.mime == "image/png" -> "png"
            item.mime == "video/mp4" -> "mp4"
            else -> "tmp"
        }
    }

    private fun decryptToFile(item: VaultItem, key: SecretKeySpec, target: File) {
        val sourceFile = File(if (item.isTrash) trashDataDir() else activeDataDir(), "${item.id}.bin")
        FileInputStream(sourceFile).use { source ->
            val iv = ByteArray(GCM_IV_BYTES)
            var offset = 0
            while (offset < iv.size) {
                val n = source.read(iv, offset, iv.size - offset)
                if (n < 0) throw IllegalStateException("File tidak lengkap")
                offset += n
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(item.id.toByteArray(Charsets.UTF_8))
            CipherInputStream(source, cipher).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun deletePreviewCache() {
        previewDir().listFiles()?.forEach { it.delete() }
    }

    private fun humanSize(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private class ExpressionParser(private val source: String) {
    private val input = source.replace("×", "*").replace("÷", "/").replace("−", "-" ).replace(" ", "")
    private var position = 0

    fun parse(): Double {
        val value = parseExpression()
        skipSpaces()
        if (position != input.length) throw IllegalArgumentException("Unexpected input")
        if (!value.isFinite()) throw IllegalArgumentException("Invalid value")
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipSpaces()
            value = when {
                eat('+') -> value + parseTerm()
                eat('-') -> value - parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parseFactor()
        while (true) {
            skipSpaces()
            value = when {
                eat('*') -> value * parseFactor()
                eat('/') -> {
                    val divisor = parseFactor()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    value / divisor
                }
                else -> return value
            }
        }
    }

    private fun parseFactor(): Double {
        skipSpaces()
        var value = when {
            eat('+') -> parseFactor()
            eat('-') -> -parseFactor()
            eat('(') -> parseExpression().also { if (!eat(')')) throw IllegalArgumentException("Missing )") }
            else -> parseNumber()
        }
        while (eat('%')) value /= 100.0
        return value
    }

    private fun parseNumber(): Double {
        skipSpaces()
        val start = position
        var dots = 0
        while (position < input.length) {
            val c = input[position]
            if (c == '.') {
                dots++
                if (dots > 1) break
                position++
            } else if (c.isDigit()) position++ else break
        }
        if (start == position) throw IllegalArgumentException("Number expected")
        return input.substring(start, position).toDouble()
    }

    private fun eat(char: Char): Boolean {
        if (position < input.length && input[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (position < input.length && input[position].isWhitespace()) position++
    }
}
