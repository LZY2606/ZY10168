package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ServiceIntegrationTest {

    private fun freshDb(dir: File): Service {
        val db = Database(File(dir, "t.db").absolutePath)
        db.init()
        val s = Service(db)
        s.ensureFixture()
        return s
    }

    @Test
    fun `two operators make opposite decisions then basis change forces replay conflict`(@TempDir dir: File) {
        val svc = freshDb(dir)
        val sid = "sess-v1-v2"
        val view0 = JsonMin.parse(svc.sessionViewJson(sid).toString()) as Map<*, *>
        val basis0 = view0["basisRevision"]
        // 找到拆词粒度组的签名
        val granSig = ((view0["groupItems"] as List<*>).first { item ->
            val m = item as Map<*, *>; m["kind"] == "GRANULARITY"
        } as Map<*, *>)["signature"] as String

        // 操作者甲选 A（合词），操作者乙从同一基线选 B（细分）
        svc.decide(sid, "甲", granSig, "USE_A", null, 0)
        svc.decide(sid, "乙", granSig, "USE_B", null, 0)

        // 甲持旧 basis 再保存 -> 不报错（追加日志），但旧决定 seq=1 必须进入重放冲突
        var view = JsonMin.parse(svc.sessionViewJson(sid).toString()) as Map<*, *>
        var conflicts = view["replayConflicts"] as List<*>
        assertEquals(1, conflicts.size)
        val c0 = conflicts.first() as Map<*, *>
        assertEquals("甲", c0["operatorId"])
        assertTrue((c0["reason"] as String).contains("相反决定"))

        // 乙修订对齐基础：拆除“读不读”组的全部链接，basis -> 1
        val links = svc.repo.links(sid).filter { it.leftTokenId == "${Fixture.V1}|t8" }
        // 先删到只剩一条再删最后一条，或一次性删三条
        links.forEach { l ->
            val current = svc.repo.session(sid)!!.basisRevision
            svc.changeBasis(sid, "乙", "REMOVE_LINK", null, null, l.id, current)
        }

        // 甲仍持 basis=0 尝试决定/合并 -> 必须 409，不能被最后一次保存覆盖
        val ex = assertThrows(Service.Conflict::class.java) {
            svc.decide(sid, "甲", granSig, "USE_A", null, 0)
        }
        assertEquals(409, ex.status)
        assertEquals("BASIS_CHANGED", ex.code)

        val exMerge = assertThrows(Service.Conflict::class.java) {
            svc.merge(sid, "甲", 0)
        }
        assertEquals(409, exMerge.status)

        // 新视图：甲/乙此前关于该组的决定都成为“对象已消失”的显式重放冲突
        view = JsonMin.parse(svc.sessionViewJson(sid).toString()) as Map<*, *>
        conflicts = view["replayConflicts"] as List<*>
        assertTrue(conflicts.size >= 2, "拆组后两个旧决定都应进入重放冲突: $conflicts")
        assertTrue(conflicts.all { ((it as Map<*, *>)["reason"] as String).let { r ->
            r.contains("对齐基础") || r.contains("相反决定") } })
        // 日志完整、不覆盖
        val logs = svc.repo.eventLogs(sid)
        assertTrue(logs.any { it.actor == "甲" && it.kind == "DECISION" })
        assertTrue(logs.any { it.actor == "乙" && it.kind == "BASIS_REMOVE_LINK" })
    }

    @Test
    fun `export wipe and reimport reproduces decisions and runs`(@TempDir dir: File) {
        val svc = freshDb(dir)
        val sid = "sess-v1-v2"
        val view0 = JsonMin.parse(svc.sessionViewJson(sid).toString()) as Map<*, *>
        val granSig = ((view0["groupItems"] as List<*>).first { (it as Map<*, *>)["kind"] == "GRANULARITY" } as Map<*, *>)["signature"] as String
        svc.decide(sid, "甲", granSig, "USE_B", null, 0)
        svc.merge(sid, "甲", 0)
        val exported = Backup.export(svc).toString()
        val expParsed = JsonMin.parse(exported) as Map<*, *>
        val sessExp = (expParsed["sessions"] as List<*>).first { (it as Map<*,*>)["id"] == sid } as Map<*,*>
        assertEquals(1, (sessExp["runs"] as List<*>).size)

        svc.db.tx { svc.writes.wipeAll() }
        svc.ensureFixture() // 重新放 fixture（模拟清空后重启）
        // 再清空，纯靠导入恢复
        svc.db.tx { svc.writes.wipeAll() }
        Backup.restore(svc, JsonMin.obj(exported))

        val view = JsonMin.parse(svc.sessionViewJson(sid).toString()) as Map<*, *>
        val decisions = view["decisions"] as List<*>
        assertEquals(1, decisions.size)
        val runs = svc.repo.runs(sid)
        assertEquals(1, runs.size)
        // 反查：新版本里细分出的“读/不/读”每个 token 都能追溯到 v2
        val newVersionId = runs.single().newVersionId
        val prov = JsonMin.parse(provenanceDump(svc, newVersionId)) as Map<*, *>
        val tokenProv = prov["tokens"] as List<*>
        val split = tokenProv.filter { t ->
            val m = t as Map<*, *>; (m["sourceTokenIds"] as String).startsWith(Fixture.V2) &&
                (m["sourceVersionId"] == Fixture.V2)
        }
        assertTrue(split.size >= 3, "细分的三个源 token 都应可追溯: $tokenProv")
    }

    private fun provenanceDump(svc: Service, versionId: String): String {
        val arr = svc.repo.tokenProv(versionId).map {
            mapOf("newTokenId" to it.newTokenId, "sourceVersionId" to it.sourceVersionId,
                "sourceTokenIds" to it.sourceTokenIds, "selectedSide" to it.selectedSide,
                "viaDecisionSeq" to it.viaSeq)
        }
        return JsonObj { put("tokens", arr) }.toString()
    }
}
