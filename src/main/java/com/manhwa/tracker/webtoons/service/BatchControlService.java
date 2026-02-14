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
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

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
    private final Map<String, Job> jobsByBatchName;

    public BatchControlService(JobLauncher jobLauncher,
                               JobExplorer jobExplorer,
                               Map<String, Job> jobsByName) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
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

    private BatchJobView toView(String jobName) {
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
}
