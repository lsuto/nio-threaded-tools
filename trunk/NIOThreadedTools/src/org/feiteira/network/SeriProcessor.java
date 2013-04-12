package org.feiteira.network;

import java.io.IOException;

public interface SeriProcessor {
	public void process(SeriDataPackage pack);
	public void shutdownCompleted();
}
