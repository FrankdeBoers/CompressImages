package com.frank.compressimage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.frank.compressimage.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.max


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // 输入路径
    private lateinit var aInputPath: String

    // 输出路径
    private lateinit var outputPath: String

    // 图片压缩参数
    private var maxWidth = 1080f      // 图片压缩限制宽度

    // 图片的大小，大于此大小才会压缩，否则直接拷贝 kb
    private var maxSize = 300 * 1024L

    // 图片压缩质量，1~100
    private var maxQuality: Int = 100

    private var totalCount = 1
    private var finishedCount = 0

    // 支持压缩的格式
    private val imageEndFixList = listOf("jpg", "jpeg", "png")

    // 拷贝失败列表
    private val copyFailedList = Collections.synchronizedList(mutableListOf<String>())

    // 图片压缩失败列表
    private val compressFailedList = Collections.synchronizedList(mutableListOf<String>())

    private val sp by lazy {
        getSharedPreferences("sp_main", Context.MODE_PRIVATE)
    }

    private val editor by lazy {
        sp.edit()
    }

    companion object {
        const val CODE_IN = 1000
        const val CODE_OUT = 1001

        const val IS_REPLACE = "is_replace"

        // 移动而不是复制
        const val IS_DELETE = "is_delete"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initClick()
        getPermission()
    }

    private fun initClick() {
        // 开始压缩指定目录的图片
        binding.btnStartCompress.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(this, "需要管理所有文件权限", Toast.LENGTH_SHORT).show()
                getPermission(false)
                return@setOnClickListener
            }
            reset()

            maxQuality = binding.edSetQuality.text.toString().toInt()
            maxSize = binding.edSetMaxSize.text.toString().toLong()
            maxWidth = binding.edSetWidth.text.toString().toFloat()

            lifecycleScope.launch {
                aInputPath = binding.pathInput.text.toString()
                outputPath = binding.pathOutput.text.toString()
                if (!File(aInputPath).exists()) {
                    File(aInputPath).mkdirs()
                }
                if (!File(outputPath).exists()) {
                    File(outputPath).mkdirs()
                }
                Log.i("", "FrankCompress# aInputPath:$aInputPath, outputPath:$outputPath")

                withContext(Dispatchers.Default) {
                    // 需要处理的文件
                    val inputFiles = mutableListOf("")
                    findAllImages(File(aInputPath), inputFiles)
                    // 已经完成处理的文件
                    val finishedFiles = mutableListOf("")
                    findAllImages(File(outputPath), finishedFiles)
                    // 路径替换，用于对比
                    val renameFinishedFiles = finishedFiles.map {
                        it.replace(outputPath, aInputPath)
                    }
                    val resultList = if (binding.replaceCheckBox.isChecked) {
                        inputFiles
                    } else {
                        inputFiles.minus(renameFinishedFiles.toSet())
                    }
                    Log.i("", "FrankCompress# inputFiles:$inputFiles")
                    Log.i("", "FrankCompress# finishedFiles:$finishedFiles")
                    Log.i("", "FrankCompress# resultList:$resultList")
                    compressAllImages(resultList)
                }
            }

        }

        // 使用系统文件夹选择
        binding.btnFolderInput.setOnClickListener {
            chooseSystemFile(CODE_IN)
        }

        // 使用系统文件夹选择
        binding.btnFolderOut.setOnClickListener {
            chooseSystemFile(CODE_OUT)
        }

        // 删除源文件
        binding.deleteSrcFile.isChecked = sp.getBoolean(IS_DELETE, false)
        binding.deleteSrcFile.setOnCheckedChangeListener { _, isChecked ->
            editor.putBoolean(IS_DELETE, isChecked)
            // 提交修改
            editor.apply() // 异步提交，无返回值
        }

        // 覆盖同名文件
        binding.replaceCheckBox.isChecked = sp.getBoolean(IS_REPLACE, false)
        binding.replaceCheckBox.setOnCheckedChangeListener { _, isChecked ->
            editor.putBoolean(IS_REPLACE, isChecked)
            // 提交修改
            editor.apply() // 异步提交，无返回值
        }
    }

    private suspend fun compressAllImages(fileList: List<String>) {
        totalCount = fileList.size
        fileList.forEach { srcFilePath ->
            withContext(Dispatchers.IO) {
                val outFilePath = srcFilePath.replace(aInputPath, outputPath)
                Log.i("", "FrankCompress# compressAllImages srcFilePath:$srcFilePath, outFilePath:$outFilePath")
                doCompress(srcFilePath, outFilePath)
                // 所有任务完成
                runOnUiThread {
                    if (finishedCount == totalCount) {
                        afterFinish()
                    }
                }
            }
        }
    }

    private fun getPermission(showToast: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.i("", "FrankCompress# isExternalStorageManager false")
                // 未拥有权限，需要申请
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent()
                        intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.setData(uri)
                        startActivity(intent)
                    } catch (e: java.lang.Exception) {
                        Log.i("", "FrankCompress# isExternalStorageManager $e")
                        Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                if (showToast) {
                    // 已经拥有权限，可以进行文件操作
                    Toast.makeText(this, "已经拥有权限，可以进行文件操作", Toast.LENGTH_SHORT).show()
                }
                Log.i("", "FrankCompress# isExternalStorageManager true")
            }
        }
    }

    private suspend fun findAllImages(dir: File, result: MutableList<String>): MutableList<String> {
        return withContext(Dispatchers.IO) {
            val files = dir.listFiles()
            if (files != null) {
                for (childFile in files) {
                    if (childFile.isDirectory) {
                        Log.i("", "FrankCompress# 文件夹 ${childFile.absolutePath}")
                        findAllImages(childFile, result)
                    } else if (childFile.isFile) {
                        result.add(childFile.absolutePath)
                    }
                }
            }
            return@withContext result
        }
    }

    /**
     * @param srcPath 图片原始路径，绝对路径 /sdcard/aInputPath/234.jpg
     * @param destFilePath 图片原始路径，绝对路径 /sdcard/aOutputPath/234.jpg
     * */
    @SuppressLint("SetTextI18n")
    private fun doCompress(srcPath: String, destFilePath: String) {
        finishedCount++
        val process = ((finishedCount.toFloat() / totalCount.toFloat()) * 100).toInt()
        Log.i("", "FrankCompress# doCompress process:$process")
        runOnUiThread {
            binding.loadingProgress.setProgress(process, true)
            binding.tvProgress.text = "$finishedCount / $totalCount"
        }
        // 无法压缩，直接拷贝
        if (!canCompress(srcPath) || !isNeedCompress(srcPath)) {
            copyFile(srcPath, destFilePath)
            return
        }
        loadAndSaveImage(this@MainActivity,
            srcPath,
            destFilePath,
            failCallback = {
                // 压缩失败，或者解析失败，直接拷贝源文件过去
                CoroutineScope(Dispatchers.IO).launch {
                    compressFailedList.add(srcPath)
                    copyFile(srcPath, destFilePath)
                }
            }
        )
    }

    // 是否支持压缩
    private fun canCompress(path: String): Boolean {
        return imageEndFixList.contains(path.substringAfterLast('.', ""))
    }

    // 是否需要压缩
    // 图片存储大于500Kb
    private fun isNeedCompress(path: String): Boolean {
        return File(path).length() > maxSize
    }

    private fun copyFile(srcFilePath: String, outFilePath: String) {
        Log.i("", "FrankCompress# 开始拷贝:$srcFilePath, size:${File(srcFilePath).length() / 1024L}Kb")
        val sourceFile = File(srcFilePath)
        val destinationFile = File(outFilePath)
        try {
            val inputStream = FileInputStream(sourceFile)
            val outputStream = FileOutputStream(destinationFile)
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
            copyFailedList.add(srcFilePath)
            Log.e("", "FrankCompress# 拷贝失败:$srcFilePath; $e, copyFailedList:$copyFailedList")
            runOnUiThread {
                Toast.makeText(this, "拷贝失败:$srcFilePath; $e", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAndSaveImage(context: Context, srcPath: String, destFilePath: String, failCallback: () -> Unit) {
        // 第一次解码，只获取图片的尺寸信息
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(srcPath, options)
        // 计算 inSampleSize
        options.inJustDecodeBounds = false
        val originWidth = options.outWidth
        val originHeight = options.outHeight

        val ratio = max(originWidth / maxWidth, 1f)
        val targetWidth = originWidth / ratio
        val targetHeight = originHeight / ratio

        Glide.with(context)
            .asBitmap()
            .override(targetWidth.toInt(), targetHeight.toInt())
            .load(srcPath)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<Bitmap>,
                    isFirstResource: Boolean,
                ): Boolean {
                    runOnUiThread {
                        Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
                    }
                    failCallback.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<Bitmap>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    try {
                        Log.i("", "FrankCompress# 开始压缩:$srcPath")
                        val srcFile = File(srcPath)
                        val destFile = File(destFilePath)
                        val parentDir = destFile.parentFile
                        Log.i("", "FrankCompress# parentDir:$parentDir")
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs()
                        }
                        FileOutputStream(destFile).use { outputStream ->
                            // 压缩质量，范围 0 - 100，数值越小压缩率越高
                            resource.compress(Bitmap.CompressFormat.JPEG, maxQuality, outputStream)
                            // 手动设置文件的修改时间
                            destFile.setLastModified(srcFile.lastModified())
                        }
                        // 删除源文件
                        if (binding.deleteSrcFile.isChecked) {
                            srcFile.delete()
                        }
                    } catch (e: Exception) {
                        failCallback.invoke()
                        Toast.makeText(context, "图片保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    return false
                }
            }).submit()
    }

    /**
     * 打开系统文件选择器
     */
    private fun chooseSystemFile(code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val treeUri = data?.data
            when (requestCode) {
                // 选择源文件夹
                CODE_IN -> {
                    // /tree/primary:极空间/log
                    treeUri?.path?.substringAfterLast(":")?.let {
                        binding.pathInput.setText("/sdcard/$it")
                    }
                }

                // 选择目标文件夹
                CODE_OUT -> {
                    // /tree/primary:极空间/log
                    treeUri?.path?.substringAfterLast(":")?.let {
                        binding.pathOutput.setText("/sdcard/$it")
                    }
                }
            }
        }
    }

    private fun reset() {
        // 重置
        compressFailedList.clear()
        copyFailedList.clear()
        finishedCount = 0
    }

    private fun afterFinish() {
        Log.i("", "FrankCompress# afterFinish compressFailedList:$compressFailedList, copyFailedList:$copyFailedList")
        binding.failed.visibility = if (compressFailedList.isNotEmpty() || copyFailedList.isNotEmpty()) View.VISIBLE else View.GONE
        binding.compressFailedList.text = "压缩失败列表:\n" + (compressFailedList.joinToString(", "))
        binding.copyFailedList.text = "拷贝失败列表:\n" + (copyFailedList.joinToString(", "))
    }
}