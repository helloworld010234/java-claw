package com.tinyclaw.adapters.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.dto.ApprovalActionRequest;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApprovalRepositoryPort approvalRepository;

    @Test
    void approvePendingApprovalReturnsSuccess() throws Exception {
        ApprovalRequest approval = ApprovalRequest.pending(
            "app-1", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        );

        when(approvalRepository.findById("app-1")).thenReturn(Optional.of(approval));

        ApprovalActionRequest request = new ApprovalActionRequest("approve", "operator confirmed");

        mockMvc.perform(post("/api/v1/approvals/app-1/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalId").value("app-1"))
            .andExpect(jsonPath("$.action").value("approve"))
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void rejectPendingApprovalReturnsRejected() throws Exception {
        ApprovalRequest approval = ApprovalRequest.pending(
            "app-2", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        );

        when(approvalRepository.findById("app-2")).thenReturn(Optional.of(approval));

        ApprovalActionRequest request = new ApprovalActionRequest("reject", "operator rejected");

        mockMvc.perform(post("/api/v1/approvals/app-2/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalId").value("app-2"))
            .andExpect(jsonPath("$.action").value("reject"))
            .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void approveNonExistentApprovalReturnsNotFound() throws Exception {
        when(approvalRepository.findById("app-999")).thenReturn(Optional.empty());

        ApprovalActionRequest request = new ApprovalActionRequest("approve", null);

        mockMvc.perform(post("/api/v1/approvals/app-999/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());
    }

    @Test
    void approveAlreadyApprovedReturnsConflict() throws Exception {
        ApprovalRequest approval = ApprovalRequest.pending(
            "app-3", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        ).approve("already approved", Instant.now());

        when(approvalRepository.findById("app-3")).thenReturn(Optional.of(approval));

        ApprovalActionRequest request = new ApprovalActionRequest("approve", null);

        mockMvc.perform(post("/api/v1/approvals/app-3/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Approval is not in PENDING status: APPROVED"));
    }

    @Test
    void rejectAlreadyRejectedReturnsConflict() throws Exception {
        ApprovalRequest approval = ApprovalRequest.pending(
            "app-4", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        ).reject("already rejected", Instant.now());

        when(approvalRepository.findById("app-4")).thenReturn(Optional.of(approval));

        ApprovalActionRequest request = new ApprovalActionRequest("reject", null);

        mockMvc.perform(post("/api/v1/approvals/app-4/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Approval is not in PENDING status: REJECTED"));
    }

    @Test
    void invalidActionReturnsBadRequest() throws Exception {
        ApprovalRequest approval = ApprovalRequest.pending(
            "app-5", "run-1", "sess-1", "tc-1", "shell_command", "ls", Instant.now()
        );

        when(approvalRepository.findById("app-5")).thenReturn(Optional.of(approval));

        ApprovalActionRequest request = new ApprovalActionRequest("invalid", null);

        mockMvc.perform(post("/api/v1/approvals/app-5/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void blankActionReturnsValidationError() throws Exception {
        ApprovalActionRequest request = new ApprovalActionRequest("", null);

        mockMvc.perform(post("/api/v1/approvals/app-1/action")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
