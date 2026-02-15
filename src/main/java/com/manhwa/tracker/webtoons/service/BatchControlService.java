package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.BatchJobView;
import com.manhwa.tracker.webtoons.model.BatchStartResponse;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class BatchControlService {
    private static final Map<String, String> JOB_LABELS = Map.of(
            "webtoonsScrapeJob", "Webtoons Views",
            "asuraScrapeJob", "Asura Followers",
            "tapasScrapeJob", "Tapas Metrics"
    );

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Job> jobsByBatchName;
    private final long staleExecutionSeconds;

    public BatchControlService(JobLauncher jobLauncher,
                               JobExplorer jobExplorer,
                               JobOperator jobOperator,
                               JdbcTemplate jdbcTemplate,
                               Map<String, Job> jobsByName,
                               @Value("${app.batch.stale-execution-seconds:300}") long staleExecutionSeconds) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
        this.jdbcTemplate = jdbcTemplate;
        this.staleExecutionSeconds = staleExecutionSeconds;
        this.jobsByBatchName = new HashMap<>();
        for (Job job : jobsByName.values()) {
            jobsByBatchName.put(job.getName(), job);
        }
    }

    public List<BatchJobView> listJobs() {
        return JOB_LABELS.keySet().stream()
                .sorted()
                .map(this::toView)
                .toList();
    }

    public BatchJobView getJob(String jobName) {
        assertKnownJob(jobName);
        return toView(jobName);
    }

    public BatchStartResponse startJob(String jobName) throws Exception {
        Job job = jobsByBatchName.get(jobName);
        if (job == null || !JOB_LABELS.containsKey(jobName)) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }

        reconcileStaleRunningExecutions(jobName);
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        if (!running.isEmpty()) {
            Long runningId = running.stream().map(JobExecution::getId).max(Comparator.naturalOrder()).orElse(null);
            throw new IllegalStateException("Job is already running (executionId=" + runningId + ")");
        }

        JobParameters params = new JobParametersBuilder()
                .addLong("startedAt", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(job, params);
        return new BatchStartResponse(jobName, execution.getId(), "Started");
    }

    public BatchStartResponse stopJob(String jobName) throws Exception {
        assertKnownJob(jobName);

        reconcileStaleRunningExecutions(jobName);
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        if (running.isEmpty()) {
            throw new IllegalStateException("Job is not running: " + jobName);
        }

        JobExecution target = running.stream()
                .max(Comparator.comparingLong(JobExecution::getId))
                .orElseThrow(() -> new IllegalStateException("Job is not running: " + jobName));
        if (target.getStatus() == BatchStatus.STOPPING) {
            return new BatchStartResponse(jobName, target.getId(), "Stop already requested");
        }

        if (target.getStatus() != BatchStatus.STARTED && target.getStatus() != BatchStatus.STARTING) {
            throw new IllegalStateException(
                    "Job cannot be stopped in status " + target.getStatus() + " (executionId=" + target.getId() + ")"
            );
        }
        final boolean accepted;
        try {
            accepted = jobOperator.stop(target.getId());
        } catch (JobExecutionNotRunningException ex) {
            throw new IllegalStateException("Job is not running (executionId=" + target.getId() + ")");
        } catch (NoSuchJobExecutionException ex) {
            throw new IllegalStateException("Unknown job execution (executionId=" + target.getId() + ")");
        }
        if (!accepted) {
            throw new IllegalStateException("Stop request was not accepted (executionId=" + target.getId() + ")");
        }

        return new BatchStartResponse(jobName, target.getId(), "Stop requested");
    }

    private BatchJobView toView(String jobName) {
        reconcileStaleRunningExecutions(jobName);
        Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
        Optional<JobExecution> latestExecution = latestExecution(jobName);

        JobExecution execution = runningExecutions.stream()
                .max(Comparator.comparingLong(JobExecution::getId))
                .or(() -> latestExecution)
                .orElse(null);

        String label = JOB_LABELS.get(jobName);
        if (execution == null) {
            return new BatchJobView(
                    jobName,
                    label,
                    false,
                    null,
                    "NEVER_RUN",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        long readCount = 0;
        long writeCount = 0;
        long filterCount = 0;
        long skipCount = 0;
        long commitCount = 0;
        LocalDateTime lastUpdatedAt = execution.getLastUpdated();

        for (StepExecution stepExecution : execution.getStepExecutions()) {
            readCount += stepExecution.getReadCount();
            writeCount += stepExecution.getWriteCount();
            filterCount += stepExecution.getFilterCount();
            skipCount += stepExecution.getReadSkipCount() + stepExecution.getProcessSkipCount() + stepExecution.getWriteSkipCount();
            commitCount += stepExecution.getCommitCount();
            if (stepExecution.getLastUpdated() != null &&
                    (lastUpdatedAt == null || stepExecution.getLastUpdated().isAfter(lastUpdatedAt))) {
                lastUpdatedAt = stepExecution.getLastUpdated();
            }
        }

        boolean running = execution.isRunning();
        String status = execution.getStatus().name();
        Integer progressPercent = progressFromStatus(execution.getStatus());

        return new BatchJobView(
                jobName,
                label,
                running,
                execution.getId(),
                status,
                execution.getExitStatus() == null ? null : execution.getExitStatus().getExitCode(),
                execution.getStartTime(),
                execution.getEndTime(),
                lastUpdatedAt,
                readCount,
                writeCount,
                filterCount,
                skipCount,
                commitCount,
                progressPercent
        );
    }

    private Optional<JobExecution> latestExecution(String jobName) {
        List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 1);
        if (instances.isEmpty()) {
            return Optional.empty();
        }

        return jobExplorer.getJobExecutions(instances.get(0)).stream()
                .max(Comparator.comparingLong(JobExecution::getId));
    }

    private void assertKnownJob(String jobName) {
        if (!JOB_LABELS.containsKey(jobName)) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }
    }

    private Integer progressFromStatus(BatchStatus status) {
        if (status == BatchStatus.COMPLETED) {
            return 100;
        }
        if (status == BatchStatus.FAILED || status == BatchStatus.STOPPED || status == BatchStatus.ABANDONED) {
            return 100;
        }
        return null;
    }

    private void reconcileStaleRunningExecutions(String jobName) {
        if (staleExecutionSeconds <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusSeconds(staleExecutionSeconds);

        Set<JobExecution> runningExecutions = jobExplorer.findRunningJobExecutions(jobName);
        for (JobExecution execution : runningExecutions) {
            if (!isStaleExecution(execution, staleThreshold)) {
                continue;
            }
            markExecutionAsFailed(execution.getId(), now);
        }
    }

    private boolean isStaleExecution(JobExecution execution, LocalDateTime staleThreshold) {
        if (execution == null || execution.getId() == null) {
            return false;
        }
        BatchStatus status = execution.getStatus();
        if (status != BatchStatus.STARTING && status != BatchStatus.STARTED && status != BatchStatus.STOPPING) {
            return false;
        }
        if (execution.getEndTime() != null) {
            return false;
        }
        LocalDateTime heartbeat = execution.getLastUpdated();
        if (heartbeat == null) {
            heartbeat = execution.getStartTime();
        }
        return heartbeat != null && heartbeat.isBefore(staleThreshold);
    }

    private void markExecutionAsFailed(Long executionId, LocalDateTime now) {
        Timestamp ts = Timestamp.valueOf(now);
        String message = "Marked as FAILED by BatchControlService: stale execution heartbeat";

        jdbcTemplate.update(
                """
                UPDATE batch_step_execution
                   SET status = 'FAILED',
                       exit_code = 'FAILED',
                       exit_message = ?,
                       end_time = COALESCE(end_time, ?),
                       last_updated = ?,
                       version = version + 1
                 WHERE job_execution_id = ?
                   AND status IN ('STARTING', 'STARTED', 'STOPPING')
                """,
                message, ts, ts, executionId
        );

        jdbcTemplate.update(
                """
                UPDATE batch_job_execution
                   SET status = 'FAILED',
                       exit_code = 'FAILED',
                       exit_message = ?,
                       end_time = COALESCE(end_time, ?),
                       last_updated = ?,
                       version = version + 1
                 WHERE job_execution_id = ?
                   AND status IN ('STARTING', 'STARTED', 'STOPPING')
                """,
                message, ts, ts, executionId
        );
    }
}
