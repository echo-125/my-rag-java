package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 评估批次实体 —— 存储一次评估任务的执行状态和汇总结果。
 */
@Entity
@Table(name = "evaluation_batch")
public class EvaluationBatchEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 测试集ID */
    @Column(name = "testset_id", nullable = false, columnDefinition = "UUID")
    private UUID testsetId;

    /** 评估配置快照（JSON） */
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "TEXT")
    private String configSnapshot;

    /** 状态：running / completed / failed */
    @Column(nullable = false, length = 20)
    private String status = "running";

    /** 总测试用例数 */
    @Column(name = "total_cases", nullable = false)
    private Integer totalCases = 0;

    /** 已完成测试用例数 */
    @Column(name = "completed_cases", nullable = false)
    private Integer completedCases = 0;

    /** Precision@K 得分 */
    @Column(name = "precision_at_k")
    private Double precisionAtK;

    /** Recall 得分 */
    @Column(name = "recall_score")
    private Double recallScore;

    /** MRR（平均倒数排名）得分 */
    @Column(name = "mrr")
    private Double mrr;

    /** 命中率 */
    @Column(name = "hit_rate")
    private Double hitRate;

    /** 平均延迟（毫秒） */
    @Column(name = "avg_latency_ms")
    private Long avgLatencyMs;

    /** 评估完成时间 */
    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    /** 错误信息 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 是否已取消 */
    @Column(name = "cancelled", nullable = false)
    private Boolean cancelled = false;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTestsetId() { return testsetId; }
    public void setTestsetId(UUID testsetId) { this.testsetId = testsetId; }
    public String getConfigSnapshot() { return configSnapshot; }
    public void setConfigSnapshot(String configSnapshot) { this.configSnapshot = configSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalCases() { return totalCases; }
    public void setTotalCases(Integer totalCases) { this.totalCases = totalCases; }
    public Integer getCompletedCases() { return completedCases; }
    public void setCompletedCases(Integer completedCases) { this.completedCases = completedCases; }
    public Double getPrecisionAtK() { return precisionAtK; }
    public void setPrecisionAtK(Double precisionAtK) { this.precisionAtK = precisionAtK; }
    public Double getRecallScore() { return recallScore; }
    public void setRecallScore(Double recallScore) { this.recallScore = recallScore; }
    public Double getMrr() { return mrr; }
    public void setMrr(Double mrr) { this.mrr = mrr; }
    public Double getHitRate() { return hitRate; }
    public void setHitRate(Double hitRate) { this.hitRate = hitRate; }
    public Long getAvgLatencyMs() { return avgLatencyMs; }
    public void setAvgLatencyMs(Long avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getCancelled() { return cancelled; }
    public void setCancelled(Boolean cancelled) { this.cancelled = cancelled; }
    public Instant getCreatedAt() { return createdAt; }
}
