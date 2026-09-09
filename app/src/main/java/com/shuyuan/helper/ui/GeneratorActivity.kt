package com.shuyuan.helper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.shuyuan.helper.R
import com.shuyuan.helper.data.AppLog
import com.shuyuan.helper.data.CheckManager
import com.shuyuan.helper.data.SourceImporter
import com.shuyuan.helper.databinding.ActivityGeneratorBinding
import com.shuyuan.helper.net.GeneratedSource
import com.shuyuan.helper.net.SourceGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 独立的「生成书源」页：网址 → 自动推断书源初稿。
 * 与检测页分开，生成结果可选「加入检测列表」回到检测页继续体检。
 */
class GeneratorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeneratorBinding
    private var lastJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeneratorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGenBack.setOnClickListener { finish() }
        binding.btnGenPrecheck.setOnClickListener {
            val url = inputUrl()
            if (url.isBlank()) {
                toast("请先输入网站地址")
                return@setOnClickListener
            }
            precheck(url, inputKeyword())
        }
        binding.btnGenStart.setOnClickListener {
            val url = inputUrl()
            if (url.isBlank()) {
                toast("请先输入网站地址")
                return@setOnClickListener
            }
            generate(url, binding.etGenKeyword.text?.toString().orEmpty())
        }
        binding.btnGenAdd.setOnClickListener { addToCheckList() }
        binding.btnGenCopy.setOnClickListener { copyJson() }
    }

    private fun inputUrl(): String = binding.etGenUrl.text?.toString().orEmpty().trim()

    private fun inputKeyword(): String = binding.etGenKeyword.text?.toString().orEmpty().trim()

    private fun setWorking(working: Boolean, statusText: String? = null) {
        binding.btnGenStart.isEnabled = !working
        binding.btnGenPrecheck.isEnabled = !working
        binding.layoutGenActions.isVisible = false
        binding.progressGen.isVisible = working
        binding.tvGenStatus.isVisible = working && !statusText.isNullOrBlank()
        if (!statusText.isNullOrBlank()) binding.tvGenStatus.text = statusText
    }

    private fun generate(url: String, keyword: String) {
        setWorking(true, getString(R.string.generator_working))
        binding.tvGenResult.isVisible = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SourceGenerator.generate(url, keyword)
            }
            setWorking(false)
            binding.tvGenResult.isVisible = true
            if (result.ok) {
                lastJson = result.json
                binding.tvGenResult.text = result.summary
                binding.layoutGenActions.isVisible = true
                AppLog.append(
                    this@GeneratorActivity,
                    AppLog.Tag.GENERATE,
                    "生成书源：$url -> 成功（搜索规则${if (result.searchUrl.isBlank()) "为空" else "已生成"}，列表自测 ${result.selfTestCount} 条）"
                )
            } else {
                lastJson = null
                binding.tvGenResult.text = "生成失败：\n${result.message}"
                binding.layoutGenActions.isVisible = false
                AppLog.append(this@GeneratorActivity, AppLog.Tag.GENERATE, "生成书源：$url -> 失败：${result.message}")
            }
        }
    }

    private fun precheck(url: String, keyword: String) {
        setWorking(true, getString(R.string.generator_prechecking))
        binding.tvGenResult.isVisible = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SourceGenerator.inspect(url, keyword)
            }
            setWorking(false)
            binding.tvGenResult.isVisible = true
            lastJson = null
            val text = buildString {
                append("适性预检：").append(result.verdict).append('\n')
                append(result.summary).append('\n')
                for (line in result.items) {
                    append("· ").append(line).append('\n')
                }
            }
            binding.tvGenResult.text = text
            AppLog.append(
                this@GeneratorActivity,
                AppLog.Tag.GENERATE,
                "适性预检：$url -> ${result.verdict}（${result.summary.take(60)}）"
            )
        }
    }

    private fun addToCheckList() {
        val json = lastJson ?: return
        if (CheckManager.running.value) {
            toast("检测正在后台进行，请先返回并停止检测")
            return
        }
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { SourceImporter.parse(json) }
            if (parsed.items.isEmpty()) {
                toast("生成结果无法加入列表")
                return@launch
            }
            val merged = CheckManager.items.value + parsed.items
            val file = withContext(Dispatchers.IO) {
                val f = File(filesDir, "import_sources.json")
                f.writeText(SourceImporter.toRawText(merged))
                f
            }
            CheckManager.importFile = file.absolutePath
            CheckManager.replaceAll(
                merged,
                "已加入自动生成的书源：${parsed.items.first().name}"
            )
            toast("已加入 ${parsed.items.size} 个书源，返回检测页后可开始检测")
            finish()
        }
    }

    private fun copyJson() {
        val json = lastJson
        if (json.isNullOrBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("生成书源", json))
        toast("已复制 JSON，可粘贴到别处人工修改")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
