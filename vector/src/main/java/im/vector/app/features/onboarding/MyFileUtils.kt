package im.vector.app.features.onboarding

import android.content.Context
import android.os.Environment
import java.io.File

object MyFileUtils {
    /**
     * 文件名
     * fileName
     */
    var fileName: String = "serverAccountFile.txt"
    /**
     * 获取路径：(data/data/应用包名/files)
     */
    fun getFileDir(contextL: Context):String {
        return contextL.filesDir.absolutePath
    }

    /**
     * 创建文件
     * filePath 文件路径
     */
    fun creatFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            file.createNewFile()
        }
    }

    /**
     * 创建文件
     * filePath 文件路径
     */
    fun creatFile(filePath: File) {
        if (!filePath.exists()) {
            filePath.createNewFile()
        }
    }
    /**
     * 文件读取
     * filePath 文件路径
     */
    fun readFile(filePath: File): String? {
        return if (!filePath.isFile) {
            null
        } else {
            filePath.readText()
        }
    }

    /**
     * 文件读取
     * strPath 文件路径
     */
    fun readFile(strPath: String): String? {
        return readFile(File(strPath))
    }
    /**
     * 写入数据
     */
    fun writeText(filePath: File, content: String) {
        creatFile(filePath)
        filePath.writeText(content)
    }

    /**
     * 追加数据
     */
    fun appendText(filePath: File, content: String) {
        creatFile(filePath)
        filePath.appendText(content)
    }

    /**
     * 追加数据
     */
    fun appendBytes(filePath: File, array: ByteArray) {
        creatFile(filePath)
        filePath.appendBytes(array)
    }
}
