package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeJson;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 官方队伍只读快照（契约 1.6.5 §8.8）：纯数据，采集与序列化分离。
 * 采集在主线程完成，序列化与发送移到后台线程，避免大队伍阻塞 tick。
 */
public final class OfficialTeamSnapshot {

    /** 单支官方队伍：UUID 一律使用 GTNHLib 原样值，不做角色翻译。 */
    public static final class Team {
        public final String officialTeamId;
        public final String name;
        public final List<String> owners;
        public final List<String> officers;
        public final List<String> members;

        public Team(String officialTeamId, String name, List<String> owners, List<String> officers,
                List<String> members) {
            this.officialTeamId = officialTeamId;
            this.name = name;
            this.owners = freeze(owners);
            this.officers = freeze(officers);
            this.members = freeze(members);
        }

        private static List<String> freeze(List<String> in) {
            return in == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(in));
        }
    }

    public final String worldRef;
    public final long gen;
    public final long seq;
    public final boolean ready;
    public final List<Team> teams;

    public OfficialTeamSnapshot(String worldRef, long gen, long seq, boolean ready, List<Team> teams) {
        this.worldRef = worldRef == null ? "" : worldRef;
        this.gen = gen;
        this.seq = seq;
        this.ready = ready;
        this.teams = teams == null ? Collections.<Team>emptyList()
                : Collections.unmodifiableList(new ArrayList<Team>(teams));
    }

    /** 内容指纹：用于去重，不含 gen/seq（代次变化单独上报）。 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(64 + teams.size() * 64);
        sb.append(ready ? '1' : '0').append('|');
        for (Team team : teams) {
            sb.append(team.officialTeamId).append('#').append(team.name).append('#');
            appendIds(sb, team.owners);
            sb.append('/');
            appendIds(sb, team.officers);
            sb.append('/');
            appendIds(sb, team.members);
            sb.append(';');
        }
        return sb.toString();
    }

    private static void appendIds(StringBuilder sb, List<String> ids) {
        List<String> sorted = new ArrayList<String>(ids);
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sorted.get(i));
        }
    }

    /** 序列化为 team.official.report 载荷；只在后台线程调用。 */
    public ObjectNode toJson() {
        ObjectNode body = NodeJson.object();
        body.put("worldRef", worldRef);
        body.put("gen", gen);
        body.put("seq", seq);
        body.put("ready", ready);
        ArrayNode rows = body.putArray("teams");
        for (Team team : teams) {
            ObjectNode row = rows.addObject();
            row.put("officialTeamId", team.officialTeamId);
            row.put("name", team.name);
            ArrayNode owners = row.putArray("owners");
            for (String id : team.owners) {
                owners.add(id);
            }
            ArrayNode officers = row.putArray("officers");
            for (String id : team.officers) {
                officers.add(id);
            }
            ArrayNode members = row.putArray("members");
            for (String id : team.members) {
                members.add(id);
            }
        }
        return body;
    }
}
