package com.team.silveragent.domain.tool;

import java.util.List;

/** 查询经过团队维护的复诊办事指南；只提供办事信息，不提供医疗判断。 */
public interface CareGuideTool {
    record GuideArticle(String title, String content) { }

    List<GuideArticle> search(String conversationId, String query, String hospital, String department);
}
