/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.ha.javax.sip.cache;

import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.Map;

import javax.sip.PeerUnavailableException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.TransactionManager;

import org.mobicents.ha.javax.sip.ClusteredSipStack;
import org.restcomm.cache.MobicentsCache;
import org.restcomm.cluster.cache.ClusteredCacheData;

import gov.nist.core.CommonLogger;
import gov.nist.core.StackLogger;
import gov.nist.javax.sip.stack.MessageChannel;
import gov.nist.javax.sip.stack.MessageProcessor;
import gov.nist.javax.sip.stack.MobicentsHASIPServerTransaction;
import gov.nist.javax.sip.stack.SIPServerTransaction;
import gov.nist.javax.sip.stack.SIPTransactionStack;

/**
 * @author jean.deruelle@gmail.com
 */
public class ServerTransactionCacheData extends ClusteredCacheData<String,Map<String,Object>> {
	private static final String APPDATA = "APPDATA";
	private static StackLogger clusteredlogger = CommonLogger.getLogger(ServerTransactionCacheData.class);
	private ClusteredSipStack clusteredSipStack;	
	private MobicentsCache mobicentsCache;
	
	public ServerTransactionCacheData(String txId, MobicentsCache mobicentsCache, ClusteredSipStack clusteredSipStack) {
		super(txId, mobicentsCache);
		this.clusteredSipStack = clusteredSipStack;
		this.mobicentsCache = mobicentsCache;
	}
	
	public SIPServerTransaction getServerTransaction() throws SipCacheException {
		SIPServerTransaction haSipServerTransaction = null;
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		//Configuration config = jbossCache.getConfiguration();
		final boolean isBuddyReplicationEnabled = mobicentsCache.isBuddyReplicationEnabled();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					clusteredlogger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }
			// Issue 1517 : http://code.google.com/p/restcomm/issues/detail?id=1517
			// Adding code to handle Buddy replication to force data gravitation   
			if(isBuddyReplicationEnabled) {     
				if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					clusteredlogger.logDebug("forcing data gravitation since buddy replication is enabled");
				}
				mobicentsCache.setForceDataGravitation(true);
			}

			Map<String,Object> transactionMetaData = getValue();
			if(transactionMetaData != null) {
				try {
					final Object dialogAppData = transactionMetaData.remove(APPDATA);
					haSipServerTransaction = createServerTransaction(getKey(), transactionMetaData, dialogAppData);
				} catch (Exception e) {
					throw new SipCacheException("A problem occured while retrieving the following server transaction " + getKey() + " from the Cache", e);
				} 
			} else {
				if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					clusteredlogger.logDebug("no child node found for transactionId " + getKey());
				}				
			}
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				clusteredlogger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					clusteredlogger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
		return haSipServerTransaction;
	}
	
	public MobicentsHASIPServerTransaction createServerTransaction(String txId, Map<String, Object> transactionMetaData, Object transactionAppData) throws SipCacheException {
		MobicentsHASIPServerTransaction haServerTransaction = null; 
		if(transactionMetaData != null) {
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("sipStack " + this + " server transaction " + txId + " is present in the distributed cache, recreating it locally");
			}
			String channelTransport = (String) transactionMetaData.get(MobicentsHASIPServerTransaction.TRANSPORT);
			if (clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug(txId + " : transport " + channelTransport);
			}
			InetAddress channelIp = (InetAddress) transactionMetaData.get(MobicentsHASIPServerTransaction.PEER_IP);
			if (clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug(txId + " : channel peer Ip address " + channelIp);
			}
			Integer channelPort = (Integer) transactionMetaData.get(MobicentsHASIPServerTransaction.PEER_PORT);
			if (clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug(txId + " : channel peer port " + channelPort);
			}
			Integer myPort = (Integer) transactionMetaData.get(MobicentsHASIPServerTransaction.MY_PORT);
			if (clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug(txId + " : my port " + myPort);
			}
			MessageChannel messageChannel = null;
			MessageProcessor[] messageProcessors = clusteredSipStack.getStackMessageProcessors();
			for (MessageProcessor messageProcessor : messageProcessors) {
				if(messageProcessor.getTransport().equalsIgnoreCase(channelTransport)) {
					try {
						messageChannel = messageProcessor.createMessageChannel(channelIp, channelPort);
					} catch (IOException e) {
						clusteredlogger.logError("couldn't recreate the message channel on ip address " 
								+ channelIp + " and port " + channelPort, e);
					}
					break;
				}
			}
			
			haServerTransaction = new MobicentsHASIPServerTransaction((SIPTransactionStack) clusteredSipStack, messageChannel);
			haServerTransaction.setBranch(txId);
			try {
				updateServerTransactionMetaData(transactionMetaData, transactionAppData, haServerTransaction, true);						
			} catch (PeerUnavailableException e) {
				throw new SipCacheException("A problem occured while retrieving the following transaction " + txId + " from the Cache", e);
			} catch (ParseException e) {
				throw new SipCacheException("A problem occured while retrieving the following transaction " + txId + " from the Cache", e);
			}
		} else {
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("sipStack " + this + " server transaction " + txId + " not found in the distributed cache");
			}
		}
		
		return haServerTransaction;
	}

	/**
	 * Update the haSipDialog passed in param with the dialogMetaData and app meta data
	 * @param transactionMetaData
	 * @param transactionAppData
	 * @param haServerTransaction
	 * @throws ParseException
	 * @throws PeerUnavailableException
	 */
	private void updateServerTransactionMetaData(Map<String, Object> transactionMetaData, Object transactionAppData, MobicentsHASIPServerTransaction haServerTransaction, boolean recreation) throws ParseException,
			PeerUnavailableException {
		haServerTransaction.setMetaDataToReplicate(transactionMetaData, recreation);
		haServerTransaction.setApplicationDataToReplicate(transactionAppData);		
	}
	
	public void putServerTransaction(SIPServerTransaction serverTransaction) throws SipCacheException {
		if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			clusteredlogger.logStackTrace();
		}
		final MobicentsHASIPServerTransaction haServerTransaction = (MobicentsHASIPServerTransaction) serverTransaction;
		final String transactionId = haServerTransaction.getTransactionId();
		if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			clusteredlogger.logDebug("put HA SIP Server Transaction " + serverTransaction + " with id " + transactionId);
		}
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					clusteredlogger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }
			
			Map<String,Object> obj = haServerTransaction.getMetaDataToReplicate();
			final Object transactionAppData = haServerTransaction.getApplicationDataToReplicate();
            if(transactionAppData != null) {
                obj.put(APPDATA, transactionAppData);
            }
            putValue(obj);
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				clusteredlogger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					clusteredlogger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
	}

	public boolean removeServerTransaction() {
		if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
			clusteredlogger.logDebug("remove HA SIP Server Transaction " + getKey());
		}
		boolean succeeded = false;
		//final Cache jbossCache = getMobicentsCache().getJBossCache();
		TransactionManager transactionManager = mobicentsCache.getTxManager();
		boolean doTx = false;
		try {
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("transaction manager :" + transactionManager);
			}
			if(transactionManager != null && transactionManager.getTransaction() == null) {
				if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
					clusteredlogger.logDebug("transaction manager begin transaction");
				}
				transactionManager.begin();				
				doTx = true;				
	        }			
			//succeeded = getNode().removeChild(transactionId);
			succeeded = remove() != null;
			if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
				clusteredlogger.logDebug("removed HA SIP Server Transaction ? " + succeeded);
			}
		} catch (Exception ex) {
			try {
				if(transactionManager != null) {
					// Let's set it no matter what.
					transactionManager.setRollbackOnly();
				}
			} catch (Exception exn) {
				clusteredlogger.logError("Problem rolling back session mgmt transaction",
						exn);
			}			
		} finally {
			if (doTx) {
				try {
					if (transactionManager.getTransaction().getStatus() != Status.STATUS_MARKED_ROLLBACK) {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("transaction manager committing transaction");
						}
						transactionManager.commit();
					} else {
						if(clusteredlogger.isLoggingEnabled(StackLogger.TRACE_DEBUG)) {
							clusteredlogger.logDebug("endBatch(): rolling back batch");
						}
						transactionManager.rollback();
					}
				} catch (RollbackException re) {
					// Do nothing here since cache may rollback automatically.
					clusteredlogger.logWarning("endBatch(): rolling back transaction with exception: "
									+ re);
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new RuntimeException(
							"endTransaction(): Caught Exception ending batch: ",
							e);
				}
			}
		}
		return succeeded;
	}
}
