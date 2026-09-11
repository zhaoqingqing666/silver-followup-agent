package com.team.silveragent.domain.tool;

import com.team.silveragent.domain.model.ToolModels.DrugKnowledge;

import java.util.List;

/**
 * 药品知识查询：按药品名称（可带规格）在知识库里做真实检索。
 * 只返回"是什么、做什么用、要注意什么"这类事实性说明，
 * 不返回剂量方案、不返回"该不该吃/换药"的建议。
 */
public interface DrugKnowledgeTool {
    List<DrugKnowledge> search(String conversationId, String drugName, String specification);
}
