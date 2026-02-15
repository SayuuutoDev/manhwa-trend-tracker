package com.manhwa.tracker.webtoons.api;

import com.manhwa.tracker.webtoons.model.BatchJobView;
import com.manhwa.tracker.webtoons.model.BatchStartResponse;
import com.manhwa.tracker.webtoons.service.BatchControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/batches")
public class BatchControlController {
    private final BatchControlService batchControlService;

    public BatchControlController(BatchControlService batchControlService) {
        this.batchControlService = batchControlService;
    }

    @GetMapping
    public List<BatchJobView> listJobs() {
        return batchControlService.listJobs();
    }

    @GetMapping("/{jobName}")
    public BatchJobView getJob(@PathVariable String jobName) {
        return batchControlService.getJob(jobName);
    }

    @PostMapping("/{jobName}/start")
    public BatchStartResponse startJob(@PathVariable String jobName) throws Exception {
        return batchControlService.startJob(jobName);
    }

    @PostMapping("/{jobName}/stop")
    public BatchStartResponse stopJob(@PathVariable String jobName) throws Exception {
        return batchControlService.stopJob(jobName);
    }
}
