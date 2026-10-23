package com.rag2agent.bootstrap.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 规则规划只启动问题实际需要的 Worker；后续可在同一接口替换成小模型规划。 */
final class ResearchPlanner {

    List<Plan> plan(String query, int limit) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Plan> plans = new ArrayList<>();
        plans.add(new Plan("document", "提炼普通文本证据、事实、时间线和引用", List.of()));
        if (contains(normalized, "表", "行", "列", "金额", "同比", "环比", "统计", "指标")) {
            plans.add(new Plan("table", "核对表格表头、数据行、单位和跨页续表", List.of("read_table_rows")));
        }
        if (contains(normalized, "关系", "路径", "关联", "上下游", "影响链", "实体", "图")) {
            plans.add(new Plan("graph", "追踪实体、关系、多跳路径和证据来源", List.of("search_knowledge_graph")));
        }
        if (contains(normalized, "图片", "图表", "照片", "截图", "视觉", "示意图")) {
            plans.add(new Plan("image", "读取图片、图表、OCR 和视觉描述证据",
                    List.of("search_knowledge_base")));
        }
        if (contains(normalized, "删除", "修改", "更新", "创建", "发送", "执行")) {
            plans.add(new Plan("tool", "识别需要执行的工具步骤，只提交审批建议",
                    List.of("delete_document", "save_agent_memory")));
        }
        if (contains(normalized, "分析", "比较", "对比", "争议", "风险")) {
            plans.add(new Plan("comparison", "检查冲突证据、缺口、口径差异和适用边界", List.of()));
        }
        return plans.stream().limit(Math.max(1, limit)).toList();
    }

    private boolean contains(String query, String... terms) {
        for (String term : terms) {
            if (query.contains(term)) {
                return true;
            }
        }
        return false;
    }

    record Plan(String type, String focus, List<String> toolWhitelist) {}
}
