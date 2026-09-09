package com.shuyuan.helper.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyuan.helper.R
import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.CheckMode
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.SourceImporter
import com.shuyuan.helper.data.SourceItem
import com.shuyuan.helper.data.SourceState
import com.shuyuan.helper.databinding.ActivityMainBinding
import com.shuyuan.helper.net.CheckService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SourceListAdapter
    private var currentImportFile: File? = null
    private var startAfterPermission = false

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }
            if (text.isNullOrBlank()) {
                toast("文件读取失败或为空")
            } else {
                importText(text)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || Build.VERSION.SDK_INT < 33) {
                if (startAfterPermission) {
                    startAfterPermission = false
                    showStartDialog()
                }
            } else {
                toast("未授予通知权限，仍可检测，但切到后台时进度通知不可见")
                if (startAfterPermission) {
                    startAfterPermission = false
                    showStartDialog()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        currentImportFile = CheckManager.importFile?.let { File(it) }

        binding.rvSources.layoutManager = LinearLayoutManager(this)
        adapter = SourceListAdapter { showSourceDetail(it) }
        binding.rvSources.adapter = adapter

        binding.btnImport.setOnClickListener { showImportOptions() }
        binding.btnStart.setOnClickListener {
            if (CheckManager.running.value) {
                CheckService.stop(this)
            } else {
                ensureNotificationPermissionAndStart()
            }
        }
        binding.btnExport.setOnClickListener { showExportDialog() }
        setupFilter()
        observeState()
    }

    private fun setupFilter() {
        binding.chipFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.first()
            adapter.filter = when (checkedId) {
                R.id.btnFilterOk -> SourceFilter.OK
                R.id.btnFilterUncertain -> SourceFilter.UNCERTAIN
                R.id.btnFilterDead -> SourceFilter.DEAD
                else -> SourceFilter.ALL
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            CheckManager.items.collect { items ->
                adapter.submit(items)
                updateSummary(items)
            }
        }
        lifecycleScope.launch {
            combine(
                CheckManager.running,
                CheckManager.progressText,
                CheckManager.finishedText
            ) { running, progressText, finishedText -> Triple(running, progressText, finishedText) }
                .collect { (running, progressText, finishedText) ->
                    binding.btnStart.isEnabled = !running && currentImportFile != null
                    binding.btnImport.isEnabled = !running
                    binding.progressBar.isVisible = running
                    binding.btnStart.text = getString(if (running) R.string.stop_check else R.string.start_check)
                    if (running) {
                        binding.tvSummary.text = progressText.ifBlank { "正在检测…" }
                    } else if (finishedText.isNotBlank()) {
                        binding.tvSummary.text = finishedText
                    }
                }
        }
        lifecycleScope.launch {
            CheckManager.progress.collect {
                binding.progressBar.progress = it
            }
        }
        updateSummary(CheckManager.items.value)
    }

    private fun updateSummary(items: List<SourceItem>) {
        if (items.isEmpty()) {
            binding.tvSummary.text = "尚未导入书源，可导入 JSON 或文本文件"
            binding.tvStats.visibility = android.view.View.GONE
            binding.btnExport.isEnabled = false
            return
        }
        val ok = items.count { it.state == SourceState.OK }
        val uncertain = items.count { it.state == SourceState.UNCERTAIN }
        val dead = items.count { it.state == SourceState.DEAD }
        val pending = items.count { !it.checked }
        binding.tvSummary.text = "已导入 ${items.size} 个书源"
        binding.tvStats.visibility = android.view.View.VISIBLE
        binding.tvStats.text =
            "可用 $ok   疑似 $uncertain   失效 $dead   待检 $pending"
        binding.btnExport.isEnabled = ok > 0 || uncertain > 0 || dead > 0
        binding.btnStart.isEnabled = currentImportFile != null && !CheckManager.running.value
    }

    private fun showImportOptions() {
        if (CheckManager.running.value) {
            toast("检测进行中，暂时不能导入")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入书源")
            .setItems(arrayOf(getString(R.string.paste_import), getString(R.string.file_import))) { _, which ->
                when (which) {
                    0 -> showPasteDialog()
                    1 -> openFile.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importText(text: String) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { SourceImporter.parse(text) }
            if (parsed.items.isEmpty()) {
                toast(parsed.message.ifBlank { "没有解析出有效书源" })
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                val f = File(filesDir, "import_sources.json")
                f.writeText(text)
                f
            }
            currentImportFile = file
            CheckManager.importFile = file.absolutePath
            CheckManager.replaceAll(parsed.items, "导入成功：共 ${parsed.items.size} 个书源")
            val skip = if (parsed.skipped > 0) "，跳过 ${parsed.skipped} 条无法识别的" else ""
            toast("已导入 ${parsed.items.size} 个书源$skip")
            updateSummary(parsed.items)
        }
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "粘贴书源 JSON 数组或单条对象"
            gravity = Gravity.TOP
            minLines = 8
            maxLines = 14
            textSize = 12f
        }
        val scroll = ScrollView(this).apply {
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val pad = (resources.displayMetrics.density * 16).toInt()
        scroll.setPadding(pad, pad, pad, pad)
        MaterialAlertDialogBuilder(this)
            .setTitle("粘贴导入书源")
            .setView(scroll)
            .setPositiveButton("导入") { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isBlank()) toast("内容为空")
                else importText(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensureNotificationPermissionAndStart() {
        if (CheckManager.running.value) {
            toast("检测正在后台进行")
            return
        }
        if (currentImportFile == null) {
            toast("请先导入书源")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            startAfterPermission = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        showStartDialog()
    }

    private fun showStartDialog() {
        if (currentImportFile == null) return
        val count = CheckManager.items.value.size
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val standard = RadioButton(this).apply {
            text = "标准检测（推荐）：检查站点并尽量按书源搜索规则发真实搜索请求，结果更准确"
            isChecked = true
        }
        val quick = RadioButton(this).apply {
            text = "快速检测：只检查书源站点/接口地址是否可达"
        }
        group.addView(standard)
        group.addView(quick)
        standard.isChecked = true
        container.addView(group)
        MaterialAlertDialogBuilder(this)
            .setTitle("开始批量检测")
            .setMessage("共 $count 个书源。检测会在前台通知中持续进行，期间请保持网络畅通。")
            .setView(container)
            .setPositiveButton("开始") { _, _ ->
                val mode = if (group.checkedRadioButtonId == quick.id) CheckMode.QUICK else CheckMode.STANDARD
                val settings = CheckSettings(mode = mode, timeoutSec = 12L, concurrency = 12)
                CheckService.start(this, currentImportFile!!.absolutePath, settings)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportDialog() {
        val items = CheckManager.items.value
        if (items.isEmpty()) {
            toast("当前没有可导出的书源")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val okCount = items.count { it.state == SourceState.OK }
        val uncertainCount = items.count { it.state == SourceState.UNCERTAIN }
        val deadCount = items.count { it.state == SourceState.DEAD }
        val rbOk = RadioButton(this).apply {
            text = "仅导出可用（$okCount 个）"
            isChecked = true
        }
        val rbOkUncertain = RadioButton(this).apply {
            text = "导出 可用 + 疑似（${okCount + uncertainCount} 个）"
        }
        val rbDead = RadioButton(this).apply {
            text = "导出失效书源（$deadCount 个，便于复核）"
        }
        group.addView(rbOk)
        group.addView(rbOkUncertain)
        group.addView(rbDead)
        container.addView(group)
        MaterialAlertDialogBuilder(this)
            .setTitle("导出书源 JSON")
            .setMessage("导出的文件与导入时格式一致，可被阅读 App 直接重新导入。")
            .setView(container)
            .setPositiveButton("导出并分享") { _, _ ->
                val json = when {
                    group.checkedRadioButtonId == rbDead.id -> SourceExporter.buildJson(items, false, true)
                    group.checkedRadioButtonId == rbOkUncertain.id -> SourceExporter.buildJson(items, true)
                    else -> SourceExporter.buildJson(items, false)
                }
                if (json == "[]") {
                    toast("按当前筛选没有可导出的书源")
                    return@setPositiveButton
                }
                SourceExporter.share(this, json, if (group.checkedRadioButtonId == rbDead.id) "失效书源" else "可用书源")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSourceDetail(item: SourceItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage(
                buildString {
                    append("地址：").append(item.url).append('\n')
                    append("类型：").append(when (item.type) {
                        1 -> "音频"; 2 -> "图片"; 3 -> "文件"; 4 -> "视频"; else -> "文本"
                    }).append('\n')
                    append("状态：").append(item.state.label).append('\n')
                    append("结果：").append(item.detail)
                    if (item.elapsedMs > 0) {
                        append("\n耗时：").append(item.elapsedMs).append(" ms")
                    }
                }
            )
            .setNeutralButton("复制该书源") { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("书源", item.json.toString()))
                toast("已复制")
            }
            .setPositiveButton("查看完整 JSON", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
