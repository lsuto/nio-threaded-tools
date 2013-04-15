package org.feiteira.network;


public interface SeriEventHandler {
	public void messageArrived(SeriDataPackage pack);
	public void shutdownCompleted();
}
