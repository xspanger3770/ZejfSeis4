package com.morce.zejfseis4.data;

import java.util.Queue;
import java.util.concurrent.Semaphore;

import com.morce.zejfseis4.main.Settings;
import com.morce.zejfseis4.main.ZejfSeis4;

import uk.me.berndporr.iirj.Butterworth;

public class DataRequest {

	private final Semaphore semaphore = new Semaphore(0);
	private boolean refill = false;
	private Thread workerThread;
	private DataManager dataManager;
	private Log[] data;

	public long headLogID;
	public long lastLogID;
	private long head;

	public Object dataMutex = new Object();

	private Butterworth filter;
	private Butterworth remover_50Hz;

	private boolean realtime;

	private long startLogID;
	private long endLogID;
	private long durationMS;
	public int id;

	public static int nextID = 0;

	private boolean nextRealtime;
	private long nextStartTime;
	private long nextEndTime;
	private long nextDuration;
	private boolean change = false;
	private long lastFilteredLogID;

	public DataRequest(DataManager dataManager, long startTime, long endTime) {
		id = nextID++;
		this.dataManager = dataManager;
		setTimes(startTime, endTime);

	}

	public DataRequest(DataManager dataManager, long durationMS) {
		id = nextID++;
		this.dataManager = dataManager;
		setDuration(durationMS);
	}

	private void initData() {
		if (data == null || getLogCount() != data.length) {
			data = new Log[getLogCount()];
			for (int i = 0; i < getLogCount(); i++) {
				data[i] = new Log(dataManager);
			}
		} else {
			for (Log l : data) {
				l.clear(dataManager);
			}
		}
		headLogID = startLogID - 1;
		lastLogID = -1;
		head = -1;
		refill = true;
		semaphore.release();
	}

	private void setDuration(long durationMS) {
		synchronized (dataMutex) {
			this.realtime = true;
			this.durationMS = durationMS;
			if (!dataManager.isLoaded()) {
				return;
			}
			this.endLogID = dataManager.getLogId(System.currentTimeMillis());
			this.startLogID = endLogID - dataManager.getLogId(durationMS) + 1;
			resetFilter();
			initData();
		}
	}

	private void setTimes(long startTime, long endTime) {
		synchronized (dataMutex) {
			this.realtime = false;
			if (!dataManager.isLoaded()) {
				return;
			}
			this.startLogID = dataManager.getLogId(startTime);
			this.endLogID = dataManager.getLogId(endTime);
			resetFilter();
			initData();
		}
	}

	public synchronized void changeDuration(long durationMS) {
		if (this.durationMS == durationMS) {
			return;
		}
		nextDuration = durationMS;
		nextRealtime = true;
		change = true;
		semaphore.release();
	}

	public synchronized void changeTimes(long startTime, long endTime) {
		if (startTime > endTime) {
			throw new IllegalArgumentException();
		}
		nextStartTime = startTime;
		nextEndTime = endTime;
		nextRealtime = false;
		change = true;
		semaphore.release();
	}

	private synchronized void checkChanges() {
		if (change) {
			System.out.println("CHCHCHCHCH");
			if (nextRealtime) {
				setDuration(nextDuration);
			} else {
				setTimes(nextStartTime, nextEndTime);
			}
			change = false;
		}
	}

	public void receiveRealtime() {
		semaphore.release();
	}

	public void refill() {
		refill = true;
		semaphore.release();
	}

	public void runWorkerThread() {
		if (workerThread != null) {
			workerThread.interrupt();
			try {
				workerThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			workerThread = null;
		}
		workerThread = new Thread("DataRequest Worker Thread") {
			public void run() {
				processData();
			};
		};
		workerThread.start();
	}

	public void resetFilter() {
		filter = new Butterworth();
		double sampleRate = dataManager.getSampleRate();
		double min = Settings.MIN_FREQUENCY;
		double max = Math.min(sampleRate / 2, Settings.MAX_FREQUENCY);
		filter.bandPass(4, sampleRate, (max + min) * 0.5, (max - min));

		if (sampleRate >= 50) {
			remover_50Hz = new Butterworth();
			remover_50Hz.bandStop(4, sampleRate, 50.0, 2.0);
		} else {
			remover_50Hz = null;
		}
	}

	private void processData() {
		System.out.println("process data start");
		while (true) {
			try {
				semaphore.acquire();
			} catch (InterruptedException e) {
				break;
			}
			checkChanges();
			synchronized (dataMutex) {
				synchronized (dataManager.dataHoursMutex) {
					if (refill) {
						headLogID = startLogID - 1;
						lastLogID = -1;
						head = -1;
						refill(getStartLogID(), getEndLogID(), false);
						refill = false;
					}
					if (isRealtime() && dataManager.lastRealtimeLogID > endLogID) {
						long logCount = getLogCount();
						this.endLogID = dataManager.lastRealtimeLogID;
						this.startLogID = endLogID - logCount + 1;
					}
					if (lastLogID != -1 && dataManager.lastRealtimeLogID > lastLogID) {
						long start = lastLogID + 1;
						long end = Math.min(getEndLogID(), dataManager.lastRealtimeLogID);
						if (end - start >= 0) {
							refill(start, end, true);
						}
					}
				}
			}
		}
	}

	private void refill(long start, long end, boolean realtime) {
		start = Math.max(start, startLogID);
		end = Math.min(endLogID, end);
		long timeS = System.currentTimeMillis();
		Queue<DataHour> test = dataManager.getDataHours(start, end);
		if (!realtime) {
			System.out.println("fill start");
			System.out.printf("Refill DataRequest #%d from %d/%d to %d/%d (%d logs) %s\n", id, start, startLogID, end,
					endLogID, (end - start + 1), refill);
			System.out.println("TEST " + test.size());
		}
		long timeS2 = System.currentTimeMillis();
		for (DataHour dh : test) {
			if (dh.isEmpty()) {
				continue;
			}
			long hourID = dh.getHourID();
			long a = ZejfSeis4.getDataManager().getLogId(ZejfSeis4.getDataManager().getMillisFromHourId(hourID));
			long b = ZejfSeis4.getDataManager().getLogId(ZejfSeis4.getDataManager().getMillisFromHourId(hourID + 1))
					- 1;
			a = Math.max(start, a);
			b = Math.min(end, b);
			if (b - a >= 0) {
				for (long id = a; id <= b; id++) {
					// 0-1
					int val = dh.getValue(ZejfSeis4.getDataManager().getMillis(id));
					Log log = getLog(id);
					if (log == null) {
						continue; //5-6
					}
					
					long gap = id - headLogID;
					
					if (val == dataManager.getErrVal()) {
						log.clear(dataManager);
					} else {
						
						long actualGap = id - lastLogID;
						if (actualGap != 1 && lastFilteredLogID != -1) {
							resetFilter();
							lastFilteredLogID = -1;
						}
						
						double filteredV = filter.filter(val);
						if (remover_50Hz != null&&Settings.MAX_FREQUENCY > 49) {
							filteredV = remover_50Hz.filter(filteredV);
						}
						log.setValues(val, filteredV);
						lastLogID = id;
						lastFilteredLogID = id;
					}
					headLogID = id;
					head += gap;
					head = (head + getLogCount()) % getLogCount();
				}
				dh.access();
			}
		}
		if (!realtime)
			System.out.println("fill took " + (System.currentTimeMillis() - timeS) + " resp. "
					+ (System.currentTimeMillis() - timeS2));
		onRefill(realtime);
	}

	private Log getLog(long logID) {
		if (data == null) {
			return null;
		}
		if (logID < getStartLogID() || logID > getEndLogID()) {
			return null;
		}

		int index = (int) (((getLogCount() + head + (logID - headLogID))) % getLogCount());
		return data[index];
	}

	public long getStartLogID() {
		return startLogID;
	}

	public long getEndLogID() {
		return endLogID;
	}

	public final int getLogCount() {
		return (int) (getEndLogID() - getStartLogID() + 1);
	}

	public void stop() {
		if (workerThread != null) {
			workerThread.interrupt();
			try {
				workerThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			workerThread = null;
		}
	}

	public boolean isRealtime() {
		return realtime;
	}

	public double getFilteredValueByLogID(long logID) {
		if (logID > lastLogID) {
			// System.err.println("#"+this.id+" after head: "+logID+"/"+lastLogID);
			return dataManager.getErrVal();
		}
		if (logID < startLogID) {
			return dataManager.getErrVal();
		}
		Log log = getLog(logID);
		if (log == null) {
			System.err.println("#" + this.id + " NULL: " + logID + "/" + lastLogID + ", " + headLogID);
			return dataManager.getErrVal();
		}
		return log.getFilteredValue();
	}

	public double getFilteredValue(long time) {
		long logID = dataManager.getLogId(time);
		return getFilteredValueByLogID(logID);
	}

	public void restart() {
		stop();
		if (realtime) {
			setDuration(durationMS);
		} else {
			setTimes(startLogID, endLogID);
		}
		System.out.println("e6");
		runWorkerThread();
		System.out.println("e7");
	}

	public void onRefill(boolean isRealtime) {

	}
}
