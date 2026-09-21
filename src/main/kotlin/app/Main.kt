package app

import java.io.File

/**
 * 句轨裁决所 —— 入口。
 *   --port 5508   监听端口（默认 5508）
 *   --db  PATH    SQLite 文件（默认 ./data/arbitration.db）
 */
fun main(args: Array<String>) {
    var port = 5508
    var dbPath = "data/arbitration.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--db" -> dbPath = args[++i]
            else -> System.err.println("未知参数: ${args[i]}")
        }
        i++
    }
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    println("句轨裁决所 启动中 …  http://127.0.0.1:$port  (db=$dbPath)")
    startServer(port, dbPath)
}
