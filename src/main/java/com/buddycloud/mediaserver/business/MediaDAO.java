package com.buddycloud.mediaserver.business;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.restlet.Request;
import org.restlet.ext.fileupload.RestletFileUpload;

import com.buddycloud.mediaserver.business.jdbc.MetadataSource;
import com.buddycloud.mediaserver.business.model.Media;
import com.buddycloud.mediaserver.commons.ConfigurationUtils;
import com.buddycloud.mediaserver.commons.Constants;
import com.buddycloud.mediaserver.commons.exception.FormMissingFieldException;
import com.buddycloud.mediaserver.commons.exception.MediaNotFoundException;
import com.buddycloud.mediaserver.commons.exception.ChecksumNotMatchingException;
import com.buddycloud.mediaserver.commons.exception.MediaMetadataSourceException;
import com.google.gson.Gson;

public class MediaDAO {

	private static Logger LOGGER = Logger.getLogger(MediaDAO.class);
	private MetadataSource dataSource;
	private Properties configuration;
	private Gson gson;


	private static final MediaDAO instance = new MediaDAO();


	private MediaDAO() {
		this.gson = new Gson();
		this.configuration = ConfigurationUtils.loadConfiguration();
		this.dataSource = new MetadataSource(configuration);
	}


	public static MediaDAO gestInstance() {
		return instance;
	}


	public File getAvatar(String entityId, String maxHeight, String maxWidth) throws MediaNotFoundException, MediaMetadataSourceException {
		String mediaId = dataSource.getEntityAvatarId(entityId);

		String fullDirectoryPath = null;

		if (maxHeight != null && maxWidth != null) {
			//TODO return a preview
		} else {
			fullDirectoryPath = getAvatarDirectory(entityId);
		}

		File media = new File(fullDirectoryPath + File.separator + mediaId);

		if (!media.exists()) {
			dataSource.deleteMedia(mediaId);
			throw new MediaNotFoundException(mediaId, entityId);
		}
		
		// Update last viewed date
		dataSource.updateMediaLastViewed(mediaId);
		
		return media;
	}


	public File getMedia(String entityId, String mediaId, String maxHeight, String maxWidth) throws MediaMetadataSourceException, MediaNotFoundException {
		String fullDirectoryPath = null;

		if (maxHeight != null && maxWidth != null) {
			//TODO return a preview
		} else {
			fullDirectoryPath = getAvatarDirectory(entityId);
		}

		File media = new File(fullDirectoryPath + File.separator + mediaId);

		if (!media.exists()) {
			throw new MediaNotFoundException(mediaId, entityId);
		}

		// Update last viewed date
		dataSource.updateMediaLastViewed(mediaId);

		return media;
	}

	public String getMediaType(String mediaId) throws MediaMetadataSourceException {
		return dataSource.getMediaMimeType(mediaId);
	}

	public String insertMedia(String entityId, Request request, boolean isAvatar) throws FileUploadException, 
	MediaMetadataSourceException, FormMissingFieldException, ChecksumNotMatchingException {

		DiskFileItemFactory factory = new DiskFileItemFactory();
		factory.setSizeThreshold(Integer.valueOf(configuration.getProperty(Constants.MEDIA_SIZE_LIMIT_PROPERTY)));

		RestletFileUpload upload = new RestletFileUpload(factory);
		List<FileItem> items = upload.parseRequest(request);

		Media media = getFormBodyField(items);

		if (media == null) {
			final FormMissingFieldException e = new FormMissingFieldException(Constants.BODY_FIELD);
			LOGGER.warn(e.getMessage());

			throw e;
		}

		// Tries to receive and store the media file binary
		receiveAndStoreBinary(items, media, entityId, isAvatar);

		LOGGER.debug("Media sucessfully added. Media ID: " + media.getId());

		return gson.toJson(media); 
	}

	private void receiveAndStoreBinary(List<FileItem> items, Media media, String entityId, boolean isAvatar) throws FileUploadException, MediaMetadataSourceException, 
	FormMissingFieldException, ChecksumNotMatchingException {

		boolean found = false;

		for (FileItem item : items) {
			if (item.getFieldName().equals(Constants.FILE_FIELD)) {
				found = true;

				// Create directory to store the file (if doesn't exist yet)
				String fullDirectoryPath = isAvatar ? getAvatarDirectory(entityId) : getMediaDirectory(entityId);
				mkdir(fullDirectoryPath);

				File file = new File(fullDirectoryPath + File.separator + media.getId());

				LOGGER.debug("Adding new media: " + file.getAbsolutePath());

				try {
					item.write(file);
				} catch (Exception e) {
					LOGGER.error(e.getMessage(), e);

					throw new FileUploadException("Error while writing the file");
				}

				String md5 = getFileMD5Checksum(file);

				if (md5 != null) {
					if (!md5.equals(media.getMd5Checksum())) {
						//remove file
						file.delete();

						throw new ChecksumNotMatchingException(media.getMd5Checksum(), md5);
					}
				}

				//TODO verify fileSize

				// /media/<name@domain.com>/<mediaId>
				//TODO set downloadUrl

				// Only stores if the file were successfully saved				
				dataSource.storeMedia(media);

				break;
			}
		}

		if (!found) {
			final FormMissingFieldException e = new FormMissingFieldException(Constants.FILE_FIELD);
			LOGGER.warn(e.getMessage());

			throw e;
		}
	}

	private String getFileMD5Checksum(File file) {
		try {
			return DigestUtils.md5Hex(FileUtils.openInputStream(file));
		} catch (IOException e) {
			LOGGER.error("Error during media MD5 checksum generation.", e);
		}

		return null;
	}

	private Media getFormBodyField(List<FileItem> items) {
		Media media = null;

		for (int i = 0; i < items.size(); i++) {
			FileItem item = items.get(i);
			if (item.getFieldName().equals(Constants.BODY_FIELD)) {
				media = gson.fromJson(item.getString(), Media.class);
				items.remove(i);

				break;
			}
		}

		return media;
	}

	private boolean mkdir(String fullDirectoryPath) {
		File directory = new File(fullDirectoryPath);
		return directory.mkdir();
	}

	private String getMediaDirectory(String entityId) {
		return configuration.getProperty(Constants.MEDIA_STORAGE_ROOT_PROPERTY) +
				File.separator + entityId;
	}
	
	private String getAvatarDirectory(String entityId) {
		return configuration.getProperty(Constants.MEDIA_STORAGE_ROOT_PROPERTY) +
				File.separator + "avatars" + File.separator + entityId;
	}
}