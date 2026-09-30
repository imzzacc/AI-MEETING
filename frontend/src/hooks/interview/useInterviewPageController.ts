import { useInterviewCameraState } from "@/hooks/interview/camera/useInterviewCameraState";
import { useInterviewResumeAnalysis } from "@/hooks/interview/resume/useInterviewResumeAnalysis";
import { useInterviewSessionFlow } from "@/hooks/interview/session/useInterviewSessionFlow";
import { useAppSelector } from "@/store/hooks";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { adaptiveInterviewService } from "@/services/adaptiveInterviewService";
import type { InterviewRetrievalScope } from "@/services/adaptiveInterviewService";

export function useInterviewPageController() {
  const [targetDurationSeconds, setTargetDurationSeconds] = useState(1800);
  const [retrievalScope, setRetrievalScope] = useState<InterviewRetrievalScope>(
    {
      role: "java-backend",
      technologyVersions: { java: "17", mysql: "8.0", redis: "general-v1" },
    },
  );
  const capabilities = useQuery({
    queryKey: ["adaptive-interview-capabilities"],
    queryFn: adaptiveInterviewService.capabilities,
    retry: false,
    staleTime: 60_000,
  });
  const { currentUser } = useAppSelector((state) => state.user);

  const sessionFlow = useInterviewSessionFlow(currentUser);
  const resumeAnalysis = useInterviewResumeAnalysis({
    retrievalScope,
    targetDurationSeconds: capabilities.data?.enabled
      ? targetDurationSeconds
      : undefined,
    interviewerSessionId: sessionFlow.interviewerSessionId,
    setInterviewerSessionId: sessionFlow.setInterviewerSessionId,
    syncNextQuestion: (sessionId) => sessionFlow.syncNextQuestion(sessionId),
    resetInterviewFlow: sessionFlow.resetInterviewFlow,
    clearInterviewError: sessionFlow.clearInterviewError,
  });
  const cameraState = useInterviewCameraState();

  return {
    timing: {
      targetDurationSeconds,
      setTargetDurationSeconds,
      retrievalScope,
      setRetrievalScope,
      enabled: capabilities.data?.enabled === true,
    },
    chat: {
      messages: sessionFlow.messages,
      input: sessionFlow.input,
      setInput: sessionFlow.setInput,
      isReady: sessionFlow.isReady,
      canAnswer: sessionFlow.canAnswer,
      isSubmitting: sessionFlow.isInterviewSubmitting,
      handleSend: sessionFlow.handleSend,
    },
    interview: {
      sessionId: sessionFlow.interviewerSessionId,
      error: sessionFlow.interviewError,
      isEnding: sessionFlow.isEndingInterview,
      currentQuestionNumber: sessionFlow.currentQuestionNumber,
      currentQuestionContent: sessionFlow.currentQuestionContent,
      isCurrentQuestionFollowUp: sessionFlow.isCurrentQuestionFollowUp,
      currentFollowUpCount: sessionFlow.currentFollowUpCount,
      isFinished: sessionFlow.isInterviewFinished,
      totalScore: sessionFlow.totalInterviewScore,
      handleEndInterview: sessionFlow.handleEndInterview,
    },
    resume: {
      fileInputRef: resumeAnalysis.fileInputRef,
      isUploading: resumeAnalysis.isResumeUploading,
      uploadStage: resumeAnalysis.resumeUploadStage,
      localFile: resumeAnalysis.resumeLocalFile,
      fileUrl: resumeAnalysis.resumeFileUrl,
      name: resumeAnalysis.resumeName,
      uploadError: resumeAnalysis.resumeUploadError,
      previewSource: resumeAnalysis.resumePreviewSource,
      previewError: resumeAnalysis.resumePreviewError,
      previewUrl: resumeAnalysis.resumeOpenPreviewUrl,
      numPages: resumeAnalysis.numPages,
      score: resumeAnalysis.resumeScore,
      interviewTypeLabel: resumeAnalysis.resolvedInterviewTypeLabel,
      suggestions: resumeAnalysis.resumeSuggestions,
      isPreviewOpen: resumeAnalysis.isResumeOpen,
      setIsPreviewOpen: resumeAnalysis.setIsResumeOpen,
      handlePreviewLoadSuccess: resumeAnalysis.handleResumePreviewLoadSuccess,
      handlePreviewLoadError: resumeAnalysis.handleResumePreviewLoadError,
      handleFileSelect: resumeAnalysis.handleResumeFileSelect,
    },
    camera: {
      isOpen: cameraState.isCameraOpen,
      isExpanded: cameraState.isCameraExpanded,
      errorCopy: cameraState.cameraErrorCopy,
      handleCameraError: cameraState.handleCameraError,
      handleToggleCamera: cameraState.handleToggleCamera,
      handleToggleExpanded: cameraState.handleToggleCameraExpanded,
    },
  };
}
