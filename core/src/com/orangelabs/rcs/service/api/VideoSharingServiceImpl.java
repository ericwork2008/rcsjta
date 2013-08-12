/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.orangelabs.rcs.service.api;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import org.gsma.joyn.vsh.INewVideoSharingListener;
import org.gsma.joyn.vsh.IVideoPlayer;
import org.gsma.joyn.vsh.IVideoSharing;
import org.gsma.joyn.vsh.IVideoSharingListener;
import org.gsma.joyn.vsh.IVideoSharingService;
import org.gsma.joyn.vsh.VideoSharingIntent;
import org.gsma.joyn.vsh.VideoSharingServiceConfiguration;

import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;

import com.orangelabs.rcs.core.Core;
import com.orangelabs.rcs.core.content.VideoContent;
import com.orangelabs.rcs.core.ims.service.richcall.video.VideoStreamingSession;
import com.orangelabs.rcs.platform.AndroidFactory;
import com.orangelabs.rcs.provider.settings.RcsSettings;
import com.orangelabs.rcs.provider.sharing.RichCall;
import com.orangelabs.rcs.provider.sharing.RichCallData;
import com.orangelabs.rcs.utils.PhoneUtils;
import com.orangelabs.rcs.utils.logger.Logger;

/**
 * Rich call API service
 * 
 * @author Jean-Marc AUFFRET
 */
public class VideoSharingServiceImpl extends IVideoSharingService.Stub {
	/**
	 * List of video sharing sessions
	 */
    private static Hashtable<String, IVideoSharing> videoSharingSessions = new Hashtable<String, IVideoSharing>();

	/**
	 * List of video sharing invitation listeners
	 */
	private RemoteCallbackList<INewVideoSharingListener> listeners = new RemoteCallbackList<INewVideoSharingListener>();

	/**
	 * The logger
	 */
	private static Logger logger = Logger.getLogger(VideoSharingServiceImpl.class.getName());

	/**
	 * Constructor
	 */
	public VideoSharingServiceImpl() {
		if (logger.isActivated()) {
			logger.info("Video sharing API is loaded");
		}
	}

	/**
	 * Close API
	 */
	public void close() {
		// Clear lists of sessions
		videoSharingSessions.clear();
	}

    /**
     * Add a video sharing session in the list
     * 
     * @param session Video sharing session
     */
	protected static void addVideoSharingSession(VideoSharingImpl session) {
		if (logger.isActivated()) {
			logger.debug("Add a video sharing session in the list (size=" + videoSharingSessions.size() + ")");
		}
		videoSharingSessions.put(session.getSharingId(), session);
	}

    /**
     * Remove a video sharing session from the list
     * 
     * @param sessionId Session ID
     */
	protected static void removeVideoSharingSession(String sessionId) {
		if (logger.isActivated()) {
			logger.debug("Remove a video sharing session from the list (size=" + videoSharingSessions.size() + ")");
		}
		videoSharingSessions.remove(sessionId);
	}

    /**
     * Get the remote phone number involved in the current call
     * 
     * @return Phone number or null if there is no call in progress
     * @throws ServerApiException
     */
	public String getRemotePhoneNumber() throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Get remote phone number");
		}

		// Test core availability
		ServerApiUtils.testCore();

		try {
			return Core.getInstance().getImsModule().getCallManager().getRemoteParty();
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}
	}

	/**
     * Receive a new video sharing invitation
     * 
     * @param session Video sharing session
     */
    public void receiveVideoSharingInvitation(VideoStreamingSession session) {
		if (logger.isActivated()) {
			logger.info("Receive video sharing invitation from " + session.getRemoteContact());
		}

        // Extract number from contact
		String number = PhoneUtils.extractNumberFromUri(session.getRemoteContact());
        VideoContent content = (VideoContent) session.getContent();

		// Update rich call history
		RichCall.getInstance().addCall(number, session.getSessionID(),
				RichCallData.EVENT_INCOMING,
				content,
    			RichCallData.STATUS_STARTED);

		// Add session in the list
		VideoSharingImpl sessionApi = new VideoSharingImpl(session);
		addVideoSharingSession(sessionApi);

		// Broadcast intent related to the received invitation
    	Intent intent = new Intent(VideoSharingIntent.ACTION_NEW_INVITATION);
    	intent.putExtra(VideoSharingIntent.EXTRA_CONTACT, number);
    	intent.putExtra(VideoSharingIntent.EXTRA_DISPLAY_NAME, session.getRemoteDisplayName());
    	intent.putExtra(VideoSharingIntent.EXTRA_SHARING_ID, session.getSessionID());
    	intent.putExtra(VideoSharingIntent.EXTRA_ENCODING, content.getEncoding());
        intent.putExtra(VideoSharingIntent.EXTRA_FORMAT, ""); // TODO
        AndroidFactory.getApplicationContext().sendBroadcast(intent);
    }
    
    /**
     * Returns the configuration of video sharing service
     * 
     * @return Configuration
     */
    public VideoSharingServiceConfiguration getConfiguration() {
    	return new VideoSharingServiceConfiguration(
    			RcsSettings.getInstance().getMaxVideoShareDuration());    	
	}

    /**
     * Shares a live video with a contact. The parameter renderer contains the video player
     * provided by the application. An exception if thrown if there is no ongoing CS call. The
     * parameter contact supports the following formats: MSISDN in national or international
     * format, SIP address, SIP-URI or Tel-URI. If the format of the contact is not supported
     * an exception is thrown.
     * 
     * @param contact Contact
     * @param player Video player
     * @param listener Video sharing event listener
     * @return Video sharing
	 * @throws ServerApiException
     */
    public IVideoSharing shareVideo(String contact, IVideoPlayer player, IVideoSharingListener listener) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Initiate a live video session with " + contact);
		}

		// Test IMS connection
		ServerApiUtils.testIms();

		try {
		     // Initiate a new session
            VideoStreamingSession session = Core.getInstance().getRichcallService()
                    .initiateLiveVideoSharingSession(contact, player);

			// Update rich call history
			RichCall.getInstance().addCall(contact, session.getSessionID(),
                    RichCallData.EVENT_OUTGOING,
	    			session.getContent(),
	    			RichCallData.STATUS_STARTED);

			// Add session in the list
			VideoSharingImpl sessionApi = new VideoSharingImpl(session);
			addVideoSharingSession(sessionApi);
			return sessionApi;
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}
	}

    /**
     * Returns a current video sharing from its unique ID
     * 
     * @return Video sharing or null if not found
     * @throws ServerApiException
     */
    public IVideoSharing getVideoSharing(String sharingId) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Get video sharing session " + sharingId);
		}

		// Test core availability
		ServerApiUtils.testCore();

		// Return a session instance
		return videoSharingSessions.get(sharingId);
	}

    /**
     * Returns the list of video sharings in progress
     * 
     * @return List of video sharings
     * @throws ServerApiException
     */
    public List<IBinder> getVideoSharings() throws ServerApiException {
    	if (logger.isActivated()) {
			logger.info("Get video sharing sessions");
		}

		// Test core availability
		ServerApiUtils.testCore();
		
		try {
			ArrayList<IBinder> result = new ArrayList<IBinder>(videoSharingSessions.size());
			for (Enumeration<IVideoSharing> e = videoSharingSessions.elements() ; e.hasMoreElements() ;) {
				IVideoSharing sessionApi = e.nextElement() ;
				result.add(sessionApi.asBinder());
			}
			return result;
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}		
	}
    
    /**
	 * Registers an video sharing invitation listener
	 * 
	 * @param listener New video sharing listener
	 * @throws ServerApiException
	 */
	public void addNewVideoSharingListener(INewVideoSharingListener listener) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Add an video sharing invitation listener");
		}

		listeners.register(listener);
	}

	/**
	 * Unregisters an video sharing invitation listener
	 * 
	 * @param listener New video sharing listener
	 * @throws ServerApiException
	 */
	public void removeNewVideoSharingListener(INewVideoSharingListener listener) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Remove an video sharing invitation listener");
		}

		listeners.unregister(listener);
	}
}