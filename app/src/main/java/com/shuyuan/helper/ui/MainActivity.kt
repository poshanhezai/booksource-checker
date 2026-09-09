package com.shuyuan.helper.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.CheckMode
import com.shuyuan.helper.data.CheckSettings
import com.shuyuan.helper.data.SourceGroup
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SourceListAdapter
    private var currentImportFile: File? = null
    private var startAfterPermission = false
    private var pendingExportJson: String? = null
    private var pendingExportLabel: String? = null

    private val createExportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = pendingExportJson ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                AppLog.append(this@MainActivity, AppLog.Tag.EXPORT, "书源已保存到文件夹：${pendingExportLabel ?: ""}")
                toast("已保存到所选位置")
            } else {
                toast("保存失败，请重试")
            }
            pendingExportJson = null
            pendingExportLabel = null
        }
    }

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

    private val openMultipleFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val parsedItems = ArrayList<SourceItem>()
                    var skipped = 0
                    for (uri in uris) {
                        val text = contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                        if (text.isBlank()) continue
                        val r = SourceImporter.parse(text)
                        parsedItems.addAll(r.items)
                        skipped += r.skipped
                    }
                    parsedItems to skipped
                }
                if (result.first.isEmpty()) {
                    toast("所选文件没有解析出有效书源")
                    return@launch
                }
                appendImported(result.first, result.second, "多文件合并")
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
        adapter = SourceListAdapter(
            onClick = { showSourceDetail(it) },
            onLongClick = { startMultiSelect(it) },
            onSelectionChanged = { updateSelectionBar() }
        )
        binding.rvSources.adapter = adapter

        binding.btnImport.setOnClickListener { showImportOptions() }
        binding.btnOpenGenerator.setOnClickListener {
            startActivity(Intent(this, GeneratorActivity::class.java))
        }
        binding.btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnStart.setOnClickListener {
            when {
                CheckManager.running.value && CheckManager.paused.value -> {
                    AppLog.append(this, AppLog.Tag.CHECK, "继续批量检测")
                    CheckService.resume(this)
                }

                CheckManager.running.value -> {
                    AppLog.append(this, AppLog.Tag.CHECK, "暂停批量检测")
                    CheckService.pause(this)
                }

                else -> ensureNotificationPermissionAndStart()
            }
        }
        binding.btnStop.setOnClickListener {
            AppLog.append(this, AppLog.Tag.CHECK, "用户停止批量检测")
            CheckService.stop(this)
        }
        binding.btnExport.setOnClickListener { showExportDialog() }
        binding.btnSelectAll.setOnClickListener { adapter.selectAllVisible() }
        binding.btnCancelSelect.setOnClickListener { exitMultiSelect() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        setupFilter()
        setupGroupFilter()
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

    private fun setupGroupFilter() {
        binding.groupFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            adapter.groupFilter = when (checkedIds.first()) {
                R.id.btnGroupNovel -> SourceGroup.NOVEL
                R.id.btnGroupComic -> SourceGroup.COMIC
                R.id.btnGroupAdult -> SourceGroup.ADULT
                R.id.btnGroupAudio -> SourceGroup.AUDIO
                R.id.btnGroupOther -> SourceGroup.OTHER
                else -> null
            }
            updateSummary(CheckManager.items.value)
        }
    }

    private fun startMultiSelect(item: SourceItem) {
        if (CheckManager.running.value) return
        adapter.enterSelectionMode()
        adapter.toggleSelected(item)
    }

    private fun exitMultiSelect() {
        adapter.exitSelectionMode()
        updateSummary(CheckManager.items.value)
    }

    private fun updateSelectionBar() {
        val selecting = adapter.selectionMode
        binding.layoutSelectBar.isVisible = selecting
        if (selecting) {
            val visibleCount = adapter.selectedVisibleCount()
            binding.tvSelectInfo.text = "已选 ${adapter.selectedCount} / 当前筛选 $visibleCount"
            binding.btnDeleteSelected.isEnabled = adapter.selectedCount > 0
            binding.btnImport.isEnabled = false
            binding.btnOpenGenerator.isEnabled = false
            binding.btnOpenLog.isEnabled = false
            binding.btnExport.isEnabled = false
            binding.btnStart.isEnabled = false
            binding.chipFilter.isEnabled = false
            binding.groupFilterGroup.isEnabled = false
        } else {
            binding.btnDeleteSelected.isEnabled = false
            binding.chipFilter.isEnabled = true
            binding.groupFilterGroup.isEnabled = true
            updateSummary(CheckManager.items.value)
        }
    }

    private fun confirmDeleteSelected() {
        val count = adapter.selectedCount
        if (count <= 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除书源")
            .setMessage("确定从列表中删除选中的 $count 个书源吗？\n\n只影响本 App 的当前列表，不会改动手机里已保存的导出文件。")
            .setPositiveButton("删除") { _, _ -> deleteSelectedItems() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedItems() {
        val current = CheckManager.items.value
        val remaining = current.filterNot { adapter.isSelected(it) }
        if (remaining.size == current.size) return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = File(filesDir, "import_sources.json")
                f.writeText(SourceImporter.toRawText(remaining))
                f
            }
            currentImportFile = if (remaining.isEmpty()) null else file
            CheckManager.importFile = if (remaining.isEmpty()) null else file.absolutePath
            CheckManager.replaceAll(remaining, "已删除 ${current.size - remaining.size} 个书源")
            adapter.exitSelectionMode()
            AppLog.append(
                this@MainActivity,
                AppLog.Tag.IMPORT,
                "删除书源：${current.size - remaining.size} 个，剩余 ${remaining.size} 个"
            )
            updateSummary(remaining)
            toast("已删除 ${current.size - remaining.size} 个书源")
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
                CheckManager.paused,
                CheckManager.progressText,
                CheckManager.finishedText
            ) { running, paused, progressText, finishedText ->
                Triple(running, paused, progressText to finishedText)
            }
                .collect { state ->
                    val running = state.first
                    val paused = state.second
                    val progressText = state.third.first
                    val finishedText = state.third.second
                    binding.btnStart.isEnabled = running || currentImportFile != null
                    binding.btnImport.isEnabled = !running
                    binding.btnOpenGenerator.isEnabled = !running
                    binding.btnOpenLog.isEnabled = !running
                    binding.btnStop.isEnabled = running
                    binding.btnExport.isEnabled = false
                    if (!running) {
                        updateSummary(CheckManager.items.value)
                    }
                    binding.progressBar.isVisible = running
                    binding.btnStart.text = getString(
                        when {
                            !running -> R.string.start_check
                            paused -> R.string.resume_check
                            else -> R.string.pause_check
                        }
                    )
                    if (running && paused) {
                        binding.tvSummary.text = "检测已暂停：点击“继续”恢复，或点“停止”结束"
                        binding.tvStats.visibility = android.view.View.VISIBLE
                    } else if (running) {
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

    override fun onResume() {
        super.onResume()
        currentImportFile = CheckManager.importFile?.let { File(it) }
        if (!CheckManager.running.value) {
            updateSummary(CheckManager.items.value)
        }
    }

    private fun updateSummary(items: List<SourceItem>) {
        if (items.isEmpty()) {
            binding.tvSummary.text = "尚未导入书源，可导入 JSON 或文本文件"
            binding.tvStats.visibility = android.view.View.GONE
            binding.btnExport.isEnabled = false
            binding.btnStop.isEnabled = CheckManager.running.value
            return
        }
        val activeGroup = adapter.groupFilter
        val pool = items.filter { activeGroup == null || it.group == activeGroup }
        val groupText = activeGroup?.label ?: "全部分组"
        val ok = pool.count { it.state == SourceState.OK }
        val uncertain = pool.count { it.state == SourceState.UNCERTAIN }
        val dead = pool.count { it.state == SourceState.DEAD }
        val pending = pool.count { !it.checked }
        binding.tvSummary.text = "$groupText · ${pool.size} 个书源（共 ${items.size} 个）"
        binding.tvStats.visibility = android.view.View.VISIBLE
            binding.tvStats.text =
                "可用 $ok   疑似 $uncertain   失效 $dead   待检 $pending"
        binding.btnExport.isEnabled = !CheckManager.running.value && pool.any { it.checked }
        binding.btnStop.isEnabled = CheckManager.running.value
        binding.btnStart.isEnabled = CheckManager.running.value || currentImportFile != null
    }

    private fun showImportOptions() {
        if (CheckManager.running.value) {
            toast("检测进行中，暂时不能导入")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入书源")
            .setItems(
                arrayOf(
                    getString(R.string.paste_import),
                    getString(R.string.file_import),
                    getString(R.string.multi_file_import),
                    getString(R.string.clear_list)
                )
            ) { _, which ->
                when (which) {
                    0 -> showPasteDialog()
                    1 -> openFile.launch(arrayOf("*/*"))
                    2 -> openMultipleFiles.launch(arrayOf("*/*"))
                    3 -> showClearListDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importText(text: String) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { SourceImporter.parse(text) }
            if (parsed.items.isEmpty()) {
                AppLog.append(
                    this@MainActivity,
                    AppLog.Tag.IMPORT,
                    "导入失败：${parsed.message.ifBlank { "没有解析出有效书源" }}"
                )
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
            appendImported(parsed.items, parsed.skipped, "导入")
        }
    }

    /** 把新书源追加进当前列表（不覆盖原有内容），实现多次 / 多文件合并。 */
    private fun appendImported(newItems: List<SourceItem>, skipped: Int, action: String) {
        if (newItems.isEmpty()) return
        lifecycleScope.launch {
            val merged = CheckManager.items.value + newItems
            val file = withContext(Dispatchers.IO) {
                val f = File(filesDir, "import_sources.json")
                f.writeText(SourceImporter.toRawText(merged))
                f
            }
            currentImportFile = file
            CheckManager.importFile = file.absolutePath
            CheckManager.replaceAll(merged, "${action}成功：新增 ${newItems.size} 个，当前共 ${merged.size} 个")
            val skipText = if (skipped > 0) "，跳过 $skipped 条无法识别的" else ""
            AppLog.append(this@MainActivity, AppLog.Tag.IMPORT, "$action：新增 ${newItems.size} 个书源$skipText，当前共 ${merged.size} 个")
            toast("${action}成功：新增 ${newItems.size} 个书源$skipText")
            updateSummary(merged)
        }
    }

    private fun showClearListDialog() {
        if (CheckManager.items.value.isEmpty()) {
            toast("当前列表本来就是空的")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("清空列表")
            .setMessage("确定清空当前所有书源吗？已保存到手机里的导出文件不受影响。")
            .setPositiveButton("清空") { _, _ ->
                CheckManager.clear()
                currentImportFile = null
                val f = File(filesDir, "import_sources.json")
                f.delete()
                AppLog.append(this, AppLog.Tag.IMPORT, "清空当前书源列表")
                updateSummary(emptyList())
                toast("已清空")
            }
            .setNegativeButton("取消", null)
            .show()
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
        val adultNote = TextView(this).apply {
            text = getString(R.string.start_adult_inspect)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, (resources.displayMetrics.density * 12).toInt(), 0, 0)
        }
        container.addView(adultNote)
        val customLabel = TextView(this).apply {
            text = getString(R.string.start_custom_adult_keywords)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, 0)
        }
        container.addView(customLabel)
        val customInput = EditText(this).apply {
            hint = getString(R.string.start_custom_adult_hint)
            textSize = 13f
            minLines = 1
            setPadding(0, (resources.displayMetrics.density * 4).toInt(), 0, 0)
        }
        container.addView(customInput)
        MaterialAlertDialogBuilder(this)
            .setTitle("开始批量检测")
            .setMessage("共 $count 个书源。检测会在前台通知中持续进行，期间请保持网络畅通。")
            .setView(container)
            .setPositiveButton("开始") { _, _ ->
                val mode = if (group.checkedRadioButtonId == quick.id) CheckMode.QUICK else CheckMode.STANDARD
                val settings = CheckSettings(
                    mode = mode,
                    timeoutSec = 12L,
                    concurrency = 12,
                    adultInspect = true,
                    customAdultKeywords = customInput.text?.toString().orEmpty()
                )
                AppLog.append(this, AppLog.Tag.CHECK, "开始批量检测：${mode.label}，共 $count 个书源")
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
        val activeGroup = adapter.groupFilter
        val pool = items.filter { activeGroup == null || it.group == activeGroup }
        if (pool.isEmpty()) {
            toast("当前分组筛选下没有书源")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val okCount = pool.count { it.state == SourceState.OK }
        val uncertainCount = pool.count { it.state == SourceState.UNCERTAIN }
        val deadCount = pool.count { it.state == SourceState.DEAD }
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
            .setMessage(
                buildString {
                    if (activeGroup != null) {
                        append("当前分组：").append(activeGroup.label).append("（共 ").append(pool.size).append(" 个）\n")
                    } else {
                        append("当前导出全部 ").append(pool.size).append(" 个书源。\n")
                    }
                    append("文件与导入时格式一致，可被阅读 App 直接重新导入。")
                }
            )
            .setView(container)
            .setPositiveButton("分享") { _, _ ->
                val json = when {
                    group.checkedRadioButtonId == rbDead.id -> SourceExporter.buildJson(pool, false, true)
                    group.checkedRadioButtonId == rbOkUncertain.id -> SourceExporter.buildJson(pool, true)
                    else -> SourceExporter.buildJson(pool, false)
                }
                if (json == "[]") {
                    toast("按当前筛选没有可导出的书源")
                    return@setPositiveButton
                }
                val label = if (group.checkedRadioButtonId == rbDead.id) "失效书源" else "可用书源"
                SourceExporter.share(this, json, label)
                AppLog.append(this, AppLog.Tag.EXPORT, "分享导出：$label")
            }
            .setNeutralButton("保存到文件夹") { _, _ ->
                val json = when {
                    group.checkedRadioButtonId == rbDead.id -> SourceExporter.buildJson(pool, false, true)
                    group.checkedRadioButtonId == rbOkUncertain.id -> SourceExporter.buildJson(pool, true)
                    else -> SourceExporter.buildJson(pool, false)
                }
                if (json == "[]") {
                    toast("按当前筛选没有可导出的书源")
                    return@setNeutralButton
                }
                val label = if (group.checkedRadioButtonId == rbDead.id) "失效书源" else "可用书源"
                val exportLabel = if (activeGroup != null) "${activeGroup.label}-$label" else label
                pendingExportJson = json
                pendingExportLabel = exportLabel
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                createExportFile.launch("${exportLabel}_$stamp.json")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSourceDetail(item: SourceItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val idx = CheckManager.items.value.indexOfFirst { it === item }
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
                    append("\n分组：").append(item.group.label)
                }
            )
            .setNeutralButton("复制该书源") { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("书源", item.json.toString()))
                toast("已复制")
            }
            .setNegativeButton("改分组") { _, _ ->
                if (idx < 0) {
                    toast("书源状态已变化，请重试")
                } else {
                    showGroupDialog(item, idx)
                }
            }
            .setPositiveButton("查看完整 JSON", null)
            .show()
    }

    private fun showGroupDialog(item: SourceItem, index: Int) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 8)
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val radios = SourceGroup.entries.map { g ->
            RadioButton(this).apply {
                text = g.label
                isChecked = g == item.group
                id = android.view.View.generateViewId()
                setOnClickListener {
                    val chosen = SourceGroup.entries.firstOrNull { it.label == text.toString() } ?: SourceGroup.OTHER
                    CheckManager.updateItem(index, item.copy(group = chosen))
                    AppLog.append(this@MainActivity, AppLog.Tag.IMPORT, "手动改分组：${item.name} -> ${chosen.label}")
                    updateSummary(CheckManager.items.value)
                    toast("已设为「${chosen.label}」")
                }
            }
        }
        radios.forEach { group.addView(it) }
        container.addView(group)
        MaterialAlertDialogBuilder(this)
            .setTitle("设置分组")
            .setMessage("分组仅用于本 App 筛选与分类导出，不会改动原始书源 JSON。")
            .setView(container)
            .setPositiveButton("完成", null)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
