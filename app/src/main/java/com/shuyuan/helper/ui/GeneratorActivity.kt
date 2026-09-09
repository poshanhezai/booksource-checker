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
        binding.btnGenStart.setOnClickListener {
            val url = binding.etGenUrl.text?.toString().orEmpty().trim()
            if (url.isBlank()) {
                toast("请先输入网站地址")
                return@setOnClickListener
            }
            generate(url, binding.etGenKeyword.text?.toString().orEmpty())
        }
        binding.btnGenAdd.setOnClickListener { addToCheckList() }
        binding.btnGenCopy.setOnClickListener { copyJson() }
    }

    private fun generate(url: String, keyword: String) {
        binding.btnGenStart.isEnabled = false
        binding.layoutGenActions.isVisible = false
        binding.tvGenResult.isVisible = false
        binding.tvGenStatus.isVisible = true
        binding.tvGenStatus.text = getString(R.string.generator_working)
        binding.progressGen.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SourceGenerator.generate(url, keyword)
            }
            binding.btnGenStart.isEnabled = true
            binding.progressGen.isVisible = false
            binding.tvGenStatus.isVisible = false
            binding.tvGenResult.isVisible = true
            if (result.ok) {
                lastJson = result.json
                binding.tvGenResult.text = result.summary
                binding.layoutGenActions.isVisible = true
            } else {
                lastJson = null
                binding.tvGenResult.text = "生成失败：\n${result.message}"
                binding.layoutGenActions.isVisible = false
            }
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
