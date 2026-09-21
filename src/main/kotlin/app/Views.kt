package app

object Views {
    fun token(t: Token) = JsonObj {
        put("id", t.id); put("versionId", t.versionId); put("ord", t.ord); put("text", t.text)
        put("lemma", t.lemma); put("pos", t.pos); put("empty", t.empty)
    }

    fun dep(d: Dep) = JsonObj {
        put("id", d.id); put("versionId", d.versionId)
        put("headId", d.headId); put("depId", d.depId); put("relation", d.relation)
    }

    fun constituent(c: Constituent) = JsonObj {
        put("id", c.id); put("versionId", c.versionId); put("label", c.label)
        put("parts", c.parts.map { part -> part.map { it } })
    }

    fun analysis(a: Analysis) = JsonObj {
        put("version", version(a.version))
        put("tokens", a.tokens.map { token(it) })
        put("deps", a.deps.map { dep(it) })
        put("constituents", a.constituents.map { constituent(it) })
        put("validation", Validator.validate(a).map { finding(it) })
    }

    fun version(v: Version) = JsonObj {
        put("id", v.id); put("sentenceId", v.sentenceId); put("label", v.label); put("kind", v.kind)
        put("parentA", v.parentA); put("parentB", v.parentB); put("mergeRunId", v.mergeRunId)
        put("note", v.note); put("createdAt", v.createdAt)
    }

    fun finding(f: Validator.Finding) = JsonObj {
        put("code", f.code); put("severity", f.severity); put("message", f.message)
    }

    fun decision(d: Decision) = JsonObj {
        put("seq", d.seq); put("operatorId", d.operatorId); put("type", d.type)
        put("ref", d.ref); put("choice", d.choice); put("payload", d.payload)
        put("itemSignature", d.itemSignature); put("itemDescription", d.itemDescription)
        put("basisRevision", d.basisRevision); put("createdAt", d.createdAt)
    }

    fun link(l: CorrLink) = JsonObj {
        put("id", l.id); put("leftTokenId", l.leftTokenId); put("rightTokenId", l.rightTokenId)
        put("source", l.source); put("operatorId", l.operatorId); put("createdAt", l.createdAt)
    }

    fun sessionView(
        s: SessionRow, left: Analysis, right: Analysis,
        state: Engine.State, links: List<CorrLink>,
    ) = JsonObj {
        put("id", s.id); put("sentenceId", s.sentenceId); put("basisRevision", s.basisRevision)
        put("left", analysis(left)); put("right", analysis(right))
        put("links", links.map { link(it) })
        put("groups", state.groups.map { g ->
            JsonObj {
                put("gid", g.gid); put("left", g.left); put("right", g.right)
            }
        })
        put("groupItems", state.groupItems.map { gi ->
            JsonObj {
                put("signature", gi.signature); put("kind", gi.kind); put("description", gi.description)
                put("leftTokens", gi.leftTokens); put("rightTokens", gi.rightTokens)
                put("attrDiffs", gi.attrDiffs.map {
                    JsonObj { put("attr", it.attr); put("left", it.left); put("right", it.right) }
                })
                val eff = state.effective[gi.signature]
                put("effective", eff?.let { decision(it) })
            }
        })
        put("edgeItems", state.edgeItems.map { ei ->
            JsonObj {
                put("signature", ei.signature); put("headGroup", ei.headGroup); put("depGroup", ei.depGroup)
                put("description", ei.description)
                put("leftRelations", ei.leftEdges.map { it.relation })
                put("rightRelations", ei.rightEdges.map { it.relation })
                val eff = state.effective[ei.signature]
                put("effective", eff?.let { decision(it) })
            }
        })
        put("constituentItems", state.constituentItems.map { ci ->
            JsonObj {
                put("signature", ci.signature); put("description", ci.description)
                put("presentLeft", ci.left != null); put("presentRight", ci.right != null)
                val eff = state.effective[ci.signature]
                put("effective", eff?.let { decision(it) })
            }
        })
        put("leftOnlyTokens", state.leftOnlyTokens)
        put("rightOnlyTokens", state.rightOnlyTokens)
        put("decisions", state.decisions.map { decision(it) })
        put("replayConflicts", state.replayConflicts.map { rc ->
            JsonObj {
                put("seq", rc.seq); put("operatorId", rc.operatorId); put("ref", rc.ref)
                put("type", rc.type); put("choice", rc.choice); put("itemDescription", rc.itemDescription)
                put("reason", rc.reason); put("atBasis", rc.atBasis); put("currentBasis", rc.currentBasis)
            }
        })
    }
}
