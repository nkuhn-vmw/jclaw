package com.jclaw.tool.builtin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {

    List<ScheduledTask> findByStatus(ScheduledTask.TaskStatus status);

    List<ScheduledTask> findByStatusAndNextFireAtBefore(
            ScheduledTask.TaskStatus status, Instant cutoff);
}
