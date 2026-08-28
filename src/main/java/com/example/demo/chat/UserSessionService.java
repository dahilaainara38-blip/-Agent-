package com.example.demo.chat;

import com.example.demo.chat.entity.UserSessionEntity;
import com.example.demo.chat.repository.mysql.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserSessionService {

    private static final Logger logger = LoggerFactory.getLogger(UserSessionService.class);

    private final UserSessionRepository sessionRepository;

    public UserSessionService(UserSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public UserSession getSession(String userId) {
        Optional<UserSessionEntity> optional = sessionRepository.findById(userId);
        if (optional.isEmpty()) {
            return null;
        }
        
        UserSessionEntity entity = optional.get();
        UserSession session = new UserSession();
        session.setUserId(entity.getUserId());
        session.setPendingImageBase64(entity.getPendingImageBase64());
        session.setImageDescription(entity.getImageDescription());
       session.setImageAnalyzed(entity.isImageAnalyzed());
       session.setLastUpdateTime(entity.getLastUpdateTime());
        
        logger.debug("Loaded session for user {}, hasPendingImage: {}, imageAnalyzed: {}", 
            userId, session.hasPendingImage(), session.isImageAnalyzed());
        return session;
    }

    public void saveSession(UserSession session) {
        session.setLastUpdateTime(LocalDateTime.now());
        
        UserSessionEntity entity = sessionRepository.findById(session.getUserId())
                .orElse(new UserSessionEntity(session.getUserId()));
        
        entity.setPendingImageBase64(session.getPendingImageBase64());
        entity.setImageDescription(session.getImageDescription());
       entity.setImageAnalyzed(session.isImageAnalyzed());
       entity.setLastUpdateTime(session.getLastUpdateTime());
        
        sessionRepository.save(entity);
        
        logger.debug("Saved session for user {}, pendingImageBase64 length: {}, imageAnalyzed: {}", 
            session.getUserId(), 
            session.getPendingImageBase64() != null ? session.getPendingImageBase64().length() : 0,
            session.isImageAnalyzed());
    }

    public void clearSession(String userId) {
        sessionRepository.deleteById(userId);
        logger.debug("Cleared session for user {}", userId);
    }

    public void clearPendingImage(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.clearPendingImage();
            saveSession(session);
            logger.debug("Cleared pending image for user {}", userId);
        }
    }

    public boolean hasPendingImage(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasPendingImage();
    }

    public boolean hasUnanalyzedImage(String userId) {
        UserSession session = getSession(userId);
        return session != null && session.hasUnanalyzedImage();
    }

    public String getPendingImageBase64(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getPendingImageBase64() : null;
    }
    
    public String getImageDescription(String userId) {
        UserSession session = getSession(userId);
        return session != null ? session.getImageDescription() : null;
    }

    public void storePendingImage(String userId, String imageBase64) {
        UserSession session = getSession(userId);
        if (session == null) {
            session = new UserSession(userId, imageBase64);
        } else {
            session.setPendingImageBase64(imageBase64);
            session.setImageAnalyzed(false);
        }
        saveSession(session);
        logger.info("Stored pending image for user {}, base64 length: {}", userId, imageBase64.length());
    }
    
    public void markImageAsAnalyzed(String userId) {
        UserSession session = getSession(userId);
        if (session != null) {
            session.setImageAnalyzed(true);
            saveSession(session);
           logger.info("Marked image as analyzed for user {}", userId);
       }
   }

}
