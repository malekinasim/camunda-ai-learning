package com.nasim.camundaai.review.service;

import com.nasim.camundaai.review.entity.ResumeReviewAttempt;
import com.nasim.camundaai.review.repository.ResumeReviewAttemptRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


public class ResumeReviewAttemptServiceTest {
    private final ResumeReviewAttemptRepository repository =mock(ResumeReviewAttemptRepository.class);
    private final ResumeReviewAttemptService service=new ResumeReviewAttemptService(repository);

    @Test
    void markWithdrawnChangesASavedResultToWithdrawn(){
        // Arrange: an attempt whose result is saved but not applied yet
       var resumeReviewAttempt=new ResumeReviewAttempt(
                "456","match","APPROVED",87.0,"agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.RESULT_SAVED
        );
        when(repository.findByTaskIdAndReviewer("456","agent-bot")).thenReturn(Optional.of(resumeReviewAttempt));
        // Act
        service.markWithdrawn("456","agent-bot");
        // Assert
        assertEquals(ResumeReviewAttempt.AttemptStatus.WITHDRAWN,resumeReviewAttempt.getStatus());
        assertNotNull(resumeReviewAttempt.getCompletedAt());
        verify(repository).save(resumeReviewAttempt);
    }

    @Test
    void markCompletedChangesASavedResultToCompleted(){
        // Arrange: an attempt whose result is saved but not applied yet
        var resumeReviewAttempt=new ResumeReviewAttempt(
                "456","match","APPROVED",87.0,"agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.RESULT_SAVED
        );
        when(repository.findByTaskIdAndReviewer("456","agent-bot")).thenReturn(Optional.of(resumeReviewAttempt));
        // Act
        service.markCompleted("456","agent-bot");
        // Assert
        assertEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED,resumeReviewAttempt.getStatus());
        assertNotNull(resumeReviewAttempt.getCompletedAt());
        verify(repository).save(resumeReviewAttempt);
    }
    @Test
    void markCompletedNotChangesStatus(){
        // Arrange: an attempt whose result was completed
        var resumeReviewAttempt=new ResumeReviewAttempt(
                "456","match","APPROVED",87.0,"agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.COMPLETED
        );
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        resumeReviewAttempt.setCompletedAt(before);
        when(repository.findByTaskIdAndReviewer("456","agent-bot"))
                .thenReturn(Optional.of(resumeReviewAttempt));
        // Act
        service.markCompleted("456","agent-bot");
        // Assert
        assertEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED,resumeReviewAttempt.getStatus());
        assertEquals(before,resumeReviewAttempt.getCompletedAt());
        verify(repository,never()).save(any());
    }
    @Test
    void reactivateIfWithdrawnMakesAWithdrawnAttemptUsableAgain() {
        // Arrange: the agent's result was withdrawn, then the task was handed back
        var attempt = new ResumeReviewAttempt(
                "456", "match", "APPROVED", 87.0, "agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.WITHDRAWN);
        attempt.setCompletedAt(LocalDateTime.now().minusHours(1));
        when(repository.save(attempt)).thenReturn(attempt);

        // Act
        var result = service.reactivateIfWithdrawn(attempt);

        // Assert
        assertEquals(ResumeReviewAttempt.AttemptStatus.RESULT_SAVED, result.getStatus());
        assertNull(result.getCompletedAt());
        verify(repository).save(attempt);
    }

    @Test
    void reactivateIfWithdrawnLeavesACompletedAttemptUnchanged() {
        // Arrange: an attempt the agent already completed
        var attempt = new ResumeReviewAttempt(
                "456", "match", "APPROVED", 87.0, "agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.COMPLETED);

        // Act
        var result = service.reactivateIfWithdrawn(attempt);

        // Assert
        assertSame(attempt, result);
        assertEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED, result.getStatus());
        verify(repository, never()).save(any());
    }

    @Test
    void markCompletedFailsWhenThereIsNoAttempt() {
        // Arrange: nothing saved for this task
        when(repository.findByTaskIdAndReviewer("456", "agent-bot")).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(IllegalStateException.class,
                () -> service.markCompleted("456", "agent-bot"));
        verify(repository, never()).save(any());
    }

    @Test
    void markWithdrawnDoesNothingWhenThereIsNoAttempt() {
        // Arrange: nothing saved for this task
        when(repository.findByTaskIdAndReviewer("456", "agent-bot")).thenReturn(Optional.empty());

        // Act: must not throw
        service.markWithdrawn("456", "agent-bot");

        // Assert
        verify(repository, never()).save(any());
    }
    @Test
    void markWithdrawnLeavesACompletedAttemptUnchanged() {
        // Arrange: an attempt whose result was already applied
        var attempt = new ResumeReviewAttempt(
                "456", "match", "APPROVED", 87.0, "agent-bot",
                null,
                ResumeReviewAttempt.AttemptStatus.COMPLETED);
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        attempt.setCompletedAt(before);
        when(repository.findByTaskIdAndReviewer("456", "agent-bot")).thenReturn(Optional.of(attempt));

        // Act
        service.markWithdrawn("456", "agent-bot");

        // Assert
        assertEquals(ResumeReviewAttempt.AttemptStatus.COMPLETED, attempt.getStatus());
        assertEquals(before, attempt.getCompletedAt());
        verify(repository, never()).save(any());
    }
}
