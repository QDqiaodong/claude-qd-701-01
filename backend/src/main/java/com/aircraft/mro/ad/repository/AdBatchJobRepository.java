package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.ad.domain.AdBatchJob;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdBatchJobRepository extends JpaRepository<AdBatchJob, Long> {

    List<AdBatchJob> findAllByOrderByStartedAtDescIdDesc();

    List<AdBatchJob> findByRevisionIdOrderByStartedAtDescIdDesc(Long revisionId);

    /** 启动时把上次异常退出遗留的进行中任务捞回来收尾 */
    List<AdBatchJob> findByStatus(String status);

    /**
     取一行任务并加悲观写锁：失败重试任务与后台 worker 各自创建/收尾时串行。
     */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select j from AdBatchJob j where j.id = :id")
    java.util.Optional<AdBatchJob> findByIdForUpdate(Long id);

    @Modifying
    @Query("update AdBatchJob j set j.status = :status, j.lastError = :lastError, j.finishedAt = :finishedAt where j.id = :id")
    void markFinished(Long id, String status, String lastError, java.time.LocalDateTime finishedAt);

    /** 单项完成即原子计数，进度永远落库，刷新页面看到的与服务端一致 */
    @Modifying
    @Query("update AdBatchJob j set j.successCount = j.successCount + :deltaOk, "
            + "j.failedCount = j.failedCount + :deltaFail where j.id = :id")
    void markProgress(Long id, int deltaOk, int deltaFail);
}
