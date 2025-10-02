package com.study.focus.assignment.service;

import com.study.focus.assignment.domain.Assignment;
import com.study.focus.assignment.domain.Submission;
import com.study.focus.assignment.dto.*;
import com.study.focus.assignment.repository.AssignmentRepository;
import com.study.focus.assignment.repository.SubmissionRepository;
import com.study.focus.common.domain.File;
import com.study.focus.common.dto.AssignmentFileResponse;
import com.study.focus.common.dto.FileDetailDto;
import com.study.focus.common.exception.BusinessException;
import com.study.focus.common.exception.CommonErrorCode;
import com.study.focus.common.exception.UserErrorCode;
import com.study.focus.common.repository.FileRepository;
import com.study.focus.common.util.S3Uploader;
import com.study.focus.study.domain.Study;
import com.study.focus.study.domain.StudyMember;
import com.study.focus.study.domain.StudyRole;
import com.study.focus.study.repository.StudyMemberRepository;
import com.study.focus.study.repository.StudyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final StudyRepository studyRepository;
    private final StudyMemberRepository studyMemberRepository;
    private final FileRepository fileRepository;
    private final S3Uploader s3Uploader;
    private final SubmissionRepository submissionRepository;

    // 과제 목록 가져오기(생성 순 내림차순 정렬)
    @Transactional
    public List<GetAssignmentsResponse> getAssignments(Long studyId, Long userId) {
        if (studyId == null || userId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }

        studyMemberRepository.findByStudyIdAndUserId(studyId, userId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        List<Assignment> assignments = assignmentRepository.findAllByStudyIdOrderByCreatedAtDesc(studyId);

        return assignments.stream().map(assignment -> new GetAssignmentsResponse(assignment.getId(), assignment.getTitle())).toList();
    }

    // 과제 생성하기
    @Transactional
    public Long createAssignment(Long studyId, Long creatorId, CreateAssignmentRequest dto) {
        Study study = studyRepository.findById(studyId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_PARAMETER));
        StudyMember creator = studyMemberRepository.findByStudyIdAndUserId(studyId, creatorId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));
        if (!creator.getRole().equals(StudyRole.LEADER)) throw new BusinessException(UserErrorCode.URL_FORBIDDEN);
        if (!dto.getDueAt().isAfter(dto.getStartAt())) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);

        Assignment assignment = Assignment.builder()
                .creator(creator)
                .startAt(dto.getStartAt())
                .dueAt(dto.getDueAt())
                .study(study)
                .title(dto.getTitle())
                .description(dto.getDescription())
                .build();

        Assignment saveAssignment = assignmentRepository.save(assignment);

        if (dto.getFiles() != null && !dto.getFiles().isEmpty()) {
            List<FileDetailDto> list = dto.getFiles().stream().map(s3Uploader::makeMetaData).toList();
            IntStream.range(0, list.size())
                    .forEach(index -> fileRepository.save(
                            File.ofAssignment(assignment, list.get(index))
                    ));
            List<String> keys = list.stream().map(FileDetailDto::getKey).toList();
            s3Uploader.uploadFiles(keys, dto.getFiles());
        }

        return saveAssignment.getId();
    }

    // 과제 상세 내용 가져오기
    public GetAssignmentDetailResponse getAssignmentDetail(Long studyId, Long assignmentId, Long userId) {
        if (studyId == null || userId == null || assignmentId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }

        studyMemberRepository.findByStudyIdAndUserId(studyId, userId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));
        Assignment assignment = assignmentRepository.findById(assignmentId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));
        List<SubmissionListResponse> submissions = submissionRepository.findSubmissionList(assignmentId);
        List<File> files = fileRepository.findAllByAssignmentId(assignmentId);
        List<AssignmentFileResponse> attachFiles = files.stream().map(a -> new AssignmentFileResponse(a.getFileKey())).toList();

        return new GetAssignmentDetailResponse(assignment.getId(),
                assignment.getTitle(),
                assignment.getDescription(),
                assignment.getStartAt(),
                assignment.getDueAt(),
                assignment.getCreatedAt(),
                attachFiles,
                submissions);
    }

    // 과제 수정하기
    @Transactional
    public void updateAssignment(Long studyId, Long assignmentId, Long creatorId, UpdateAssignmentRequest dto) {
        StudyMember creator = studyMemberRepository.findByStudyIdAndUserId(studyId, creatorId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));
        if (!creator.getRole().equals(StudyRole.LEADER)) throw new BusinessException(UserErrorCode.URL_FORBIDDEN);
        Assignment assignment = assignmentRepository.findByIdAndStudyId(assignmentId, studyId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_PARAMETER));
        if (!dto.getDueAt().isAfter(dto.getStartAt())) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);

        assignment.update(dto.getTitle(), dto.getDescription(), dto.getStartAt(), dto.getDueAt());

        if (dto.getDeleteFileIds() != null && !dto.getDeleteFileIds().isEmpty()) {
            List<File> deleteFiles = fileRepository.findAllById(dto.getDeleteFileIds());
            deleteFiles.forEach(File::deleteAssignmentFile);
            fileRepository.saveAll(deleteFiles);
            fileRepository.flush();
        }

        if (dto.getFiles() != null && !dto.getFiles().isEmpty()) {
            List<FileDetailDto> list = dto.getFiles().stream().map(s3Uploader::makeMetaData).toList();
            IntStream.range(0, list.size())
                    .forEach(index -> fileRepository.save(
                            File.ofAssignment(assignment, list.get(index))
                    ));
            List<String> keys = list.stream().map(FileDetailDto::getKey).toList();
            s3Uploader.uploadFiles(keys, dto.getFiles());
        }
    }

    //과제 삭제하기
    @Transactional
    public void deleteAssignment(Long studyId, Long assignmentId, Long userId) {

        if (studyId == null || userId == null || assignmentId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }

        StudyMember user = studyMemberRepository.findByStudyIdAndUserId(studyId, userId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));
        if (!user.getRole().equals(StudyRole.LEADER)) throw new BusinessException(UserErrorCode.URL_FORBIDDEN);
        Assignment assignment = assignmentRepository.findByIdAndStudyId(assignmentId, studyId).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        List<Submission> submissions = submissionRepository.findAllByAssignmentId(assignmentId);
        List<Long> submissionIds = submissions.stream().map(Submission::getId).toList();

        if (!submissionIds.isEmpty()) {
            List<File> submissionFiles = fileRepository.findAllBySubmissionIdIn(submissionIds);
            if (!submissionFiles.isEmpty()) {
                submissionFiles.forEach(File::deleteSubmissionFile);
                fileRepository.saveAll(submissionFiles);
            }
            submissionRepository.deleteAll(submissions);
        }

        List<File> assignmentFiles = fileRepository.findAllByAssignmentId(assignmentId);
        if (!assignmentFiles.isEmpty()) {
            assignmentFiles.forEach(File::deleteAssignmentFile);
            fileRepository.saveAll(assignmentFiles);
        }

        assignmentRepository.delete(assignment);
    }
}
