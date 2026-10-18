package com.sfyc.demo.resume

import java.io.IOException
import kotlinx.coroutines.delay

/**
 * 简历 Demo 假数据源：上半固定 500ms 成功，下半按场景 + 延迟档故障注入。
 *
 * 人物取自《西游记》：孙悟空 / 猪八戒，各配四段经历。
 * 无状态：ERROR_ONCE 的“首败次成”由调用方传入的 attempt 区分，
 * ViewModel 负责在新一轮数据集中从 0 计数，重试递增。
 */
class FakeResumeRepository {

    private val profiles = mapOf(
        ResumeUiState.CANDIDATE_LIN to ResumeProfile(
            candidateId = ResumeUiState.CANDIDATE_LIN,
            name = "孙悟空",
            roleTitle = "取经团队 · 大师兄",
            location = "花果山水帘洞 · 齐天大圣",
            statusBadge = "取经归来",
            bio = "石头缝里蹦出的美猴王，大闹过天宫、压过五行山，护送师父西天取经，一路降妖除魔。",
            skills = listOf("七十二变", "筋斗云", "火眼金睛", "如意金箍棒"),
            highlights = listOf(
                ResumeHighlight("护送取经", "十万八千里"),
                ResumeHighlight("大闹天宫", "十万天兵莫能敌"),
            ),
        ),
        ResumeUiState.CANDIDATE_SU to ResumeProfile(
            candidateId = ResumeUiState.CANDIDATE_SU,
            name = "猪八戒",
            roleTitle = "取经团队 · 二师兄",
            location = "高老庄 · 净坛使者",
            statusBadge = "在职看机会",
            bio = "原天河水军统领，错投猪胎后高老庄入赘，随唐僧取经，负责化缘探路、挑担后勤。",
            skills = listOf("三十六变", "九齿钉耙", "化缘", "挑担后勤"),
            highlights = listOf(
                ResumeHighlight("天河水军", "统领八万水兵"),
                ResumeHighlight("化缘", "全队次数第一"),
            ),
        ),
    )

    private fun workItemsFor(candidateId: String): List<WorkExperience> =
        if (candidateId == ResumeUiState.CANDIDATE_SU) bajieWorkItems() else wukongWorkItems()

    private fun wukongWorkItems(): List<WorkExperience> = listOf(
        WorkExperience(
            id = "huaguoshan",
            company = "花果山水帘洞",
            title = "美猴王",
            period = "亘古 — 大闹天宫前",
            summary = "率领猴子猴孙操练兵马，出海学艺归来，龙宫夺宝、自封齐天大圣。",
            techStack = "金箍棒 · 七十二变 · 筋斗云",
        ),
        WorkExperience(
            id = "tianting",
            company = "天庭御马监",
            title = "弼马温 → 齐天大圣",
            period = "蟠桃会前后",
            summary = "嫌官小反下天宫，搅乱蟠桃盛会、大闹天宫，十万天兵天将莫能敌。",
            techStack = "身外身法 · 金刚不坏之身",
        ),
        WorkExperience(
            id = "wuxingshan",
            company = "五行山下",
            title = "面壁思过",
            period = "被压五百年",
            summary = "被如来压于五行山下，以铁丸铜汁为食，静候取经人到来。",
            techStack = "忍耐 · 反思 · 等待时机",
        ),
        WorkExperience(
            id = "qujinglu",
            company = "取经之路",
            title = "降妖先锋",
            period = "贞观十三年 — 历时十四年",
            summary = "保护唐僧西天取经，三打白骨精、大战红孩儿，终修成斗战胜佛。",
            techStack = "火眼金睛 · 紧箍咒（被动）",
        ),
    )

    private fun bajieWorkItems(): List<WorkExperience> = listOf(
        WorkExperience(
            id = "tianhe",
            company = "天庭天河水府",
            title = "天蓬元帅",
            period = "下凡之前",
            summary = "统领天河八万水兵，执掌天河水军，因醉酒调戏嫦娥被贬下凡。",
            techStack = "九齿钉耙 · 水战 · 领兵",
        ),
        WorkExperience(
            id = "gaolaozhuang",
            company = "高老庄",
            title = "上门女婿",
            period = "错投猪胎后",
            summary = "错投猪胎长成猪妖模样，高老庄入赘，被孙悟空降服后皈依取经队伍。",
            techStack = "三十六变 · 庄稼活",
        ),
        WorkExperience(
            id = "qujinghouqin",
            company = "取经之路",
            title = "化缘先锋 · 挑担后勤",
            period = "贞观十三年 — 历时十四年",
            summary = "随队西行，负责化缘探路、挑担守夜，好吃懒做但关键时刻从不掉链子。",
            techStack = "化缘 · 挑担 · 哄师父开心",
        ),
        WorkExperience(
            id = "jingtanshizhe",
            company = "灵山大雷音寺",
            title = "净坛使者",
            period = "取经功成后",
            summary = "因一路挑担有功受封净坛使者，专司净坛受供，心满意足。",
            techStack = "受供 · 净坛",
        ),
    )

    fun cachedProfile(candidateId: String): ResumeProfile =
        requireNotNull(profiles[candidateId]) { "未知候选人=$candidateId" }

    suspend fun getProfile(candidateId: String): ResumeProfile {
        delay(500L)
        return cachedProfile(candidateId)
    }

    suspend fun getWorkExperiences(
        candidateId: String,
        scenario: ResumeScenario,
        latencyMillis: Long,
        attempt: Int,
    ): List<WorkExperience> {
        delay(latencyMillis.coerceAtLeast(0L))
        return when (scenario) {
            ResumeScenario.SUCCESS -> workItemsFor(candidateId)
            ResumeScenario.EMPTY -> emptyList()
            ResumeScenario.ERROR_ONCE -> if (attempt == 0) {
                throw IOException("模拟请求失败")
            } else {
                workItemsFor(candidateId)
            }
            ResumeScenario.ERROR_ALWAYS -> throw IOException("模拟请求失败")
        }
    }
}
