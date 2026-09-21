package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

fun startServer(port: Int, dbPath: String) {
    val db = Database(dbPath)
    db.init()
    val service = Service(db)
    service.ensureFixture()
    embeddedServer(Netty, port = port, host = "127.0.0.1") { web(service) }.start(wait = true)
}

fun Application.web(service: Service) {
    val pageHtml = object {}::class.java.getResource("/web/index.html")?.readText()
        ?: error("index.html 不在资源中")

    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest,
                JsonObj { put("error", "BAD_REQUEST"); put("message", e.message ?: "参数错误") }.toString())
        }
    }

    suspend fun io.ktor.server.routing.RoutingContext.bad(e: Service.Conflict) {
        val body = JsonObj {
            put("error", e.code); put("message", e.message)
            if (e.body != null) put("detail", e.body.toString())
        }.toString()
        call.respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(e.status))
    }

    routing {
        get("/") { call.respondText(pageHtml, ContentType.Text.Html) }
        get("/api/state") {
            try {
                val sentences = service.repo.sentences().map { (id, surface, note) ->
                    JsonObj { put("id", id); put("surface", surface); put("note", note) }
                }
                val sessions = service.repo.sessions().map { s ->
                    JsonObj { put("id", s.id); put("sentenceId", s.sentenceId)
                        put("leftVersionId", s.leftVersionId); put("rightVersionId", s.rightVersionId)
                        put("basisRevision", s.basisRevision) }
                }
                call.respondText(JsonObj { put("sentences", sentences); put("sessions", sessions) }.toString(),
                    ContentType.Application.Json)
            } catch (e: Service.Conflict) { bad(e) }
        }
        get("/api/session/{id}") {
            try { call.respondText(service.sessionViewJson(call.parameters["id"]!!).toString(), ContentType.Application.Json) }
            catch (e: Service.Conflict) { bad(e) }
        }
        get("/api/version/{id}") {
            val a = service.repo.analysis(call.parameters["id"]!!)
                ?: return@get call.respondText("{\"error\":\"NOT_FOUND\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
            call.respondText(Views.analysis(a).toString(), ContentType.Application.Json)
        }
        post("/api/session/{id}/decide") {
            val b = JsonMin.obj(call.receiveText())
            try {
                val d = service.decide(
                    call.parameters["id"]!!,
                    b["operatorId"] as? String ?: "anonymous",
                    b["ref"] as String, b["choice"] as String,
                    b["payload"] as String?, (b["expectedBasis"] as? Number)?.toInt())
                call.respondText(Views.decision(d).toString(), ContentType.Application.Json)
            } catch (e: Service.Conflict) { bad(e) }
        }
        post("/api/session/{id}/basis") {
            val b = JsonMin.obj(call.receiveText())
            try {
                val out = service.changeBasis(
                    call.parameters["id"]!!, b["operatorId"] as? String ?: "anonymous",
                    b["action"] as String, b["leftTokenId"] as String?, b["rightTokenId"] as String?,
                    (b["linkId"] as? Number)?.toLong(), (b["expectedBasis"] as Number).toInt())
                call.respondText(JsonObj.writeText(out), ContentType.Application.Json)
            } catch (e: Service.Conflict) { bad(e) }
        }
        post("/api/session/{id}/merge") {
            val b = JsonMin.obj(call.receiveText())
            try {
                val view = service.merge(call.parameters["id"]!!,
                    b["operatorId"] as? String ?: "anonymous", (b["expectedBasis"] as? Number)?.toInt())
                call.respondText(view.toString(), ContentType.Application.Json)
            } catch (e: Service.Conflict) { bad(e) }
        }
        get("/api/session/{id}/logs") {
            val logs = service.repo.eventLogs(call.parameters["id"]).map { l ->
                JsonObj { put("id", l.id); put("ts", l.ts); put("actor", l.actor); put("kind", l.kind)
                    put("detail", l.detail) }
            }
            call.respondText(JsonObj { put("logs", logs) }.toString(), ContentType.Application.Json)
        }
        get("/api/runs/{sessionId}") {
            val rows = service.repo.runs(call.parameters["sessionId"]!!).map { r ->
                JsonObj {
                    put("id", r.id); put("newVersionId", r.newVersionId); put("operatorId", r.operatorId)
                    put("basisRevision", r.basisRevision); put("createdAt", r.createdAt)
                    put("unresolved", service.repo.unresolved(r.id).map {
                        JsonObj { put("category", it.category); put("description", it.description); put("reason", it.reason) }
                    })
                }
            }
            call.respondText(JsonObj { put("runs", rows) }.toString(), ContentType.Application.Json)
        }
        get("/api/provenance/{versionId}") {
            call.respondText(provenance(service, call.parameters["versionId"]!!), ContentType.Application.Json)
        }
        get("/api/export") {
            call.response.headers.append("Content-Disposition", "attachment; filename=\"arbitration-export.json\"")
            call.respondText(Backup.export(service).toString(), ContentType.Application.Json)
        }
        post("/api/import") {
            val text = call.receiveText()
            try {
                Backup.restore(service, JsonMin.obj(text))
                call.respondText("{\"ok\":true}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText("{\"error\":\"IMPORT_FAILED\",\"message\":\"${e.message?.escape()}\"}",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        }
        post("/api/reset") {
            service.db.tx {
                service.writes.wipeAll()
            }
            service.ensureFixture()
            call.respondText("{\"ok\":true,\"reseeded\":true}", ContentType.Application.Json)
        }
    }
}

private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")

private fun JsonObj.Companion.writeText(v: Any?): String {
    val sb = StringBuilder(); writeValue(sb, v); return sb.toString()
}

private fun provenance(service: Service, versionId: String): String {
    val tokens = service.repo.tokenProv(versionId).map { p -> JsonObj {
        put("newTokenId", p.newTokenId); put("runId", p.runId)
        put("sourceVersionId", p.sourceVersionId); put("sourceTokenIds", p.sourceTokenIds)
        put("selectedSide", p.selectedSide); put("viaDecisionSeq", p.viaSeq); put("attrSide", p.attrSide)
    } }
    val deps = service.repo.depProv(versionId).map { p -> JsonObj {
        put("newDepId", p.newDepId); put("sourceDepId", p.sourceDepId)
        put("sourceVersionId", p.sourceVersionId); put("selectedSide", p.selectedSide)
        put("viaDecisionSeq", p.viaSeq); put("runId", p.runId)
    } }
    val cons = service.repo.conProv(versionId).map { p -> JsonObj {
        put("newConstituentId", p.newConstituentId); put("sourceConstituentId", p.sourceConstituentId)
        put("sourceVersionId", p.sourceVersionId); put("selectedSide", p.selectedSide)
        put("viaDecisionSeq", p.viaSeq); put("runId", p.runId)
    } }
    return JsonObj { put("versionId", versionId); put("tokens", tokens); put("deps", deps); put("constituents", cons) }.toString()
}
