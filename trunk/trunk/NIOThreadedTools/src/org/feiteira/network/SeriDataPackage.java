package org.feiteira.network;

import java.io.Serializable;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class SeriDataPackage {
	private SelectionKey key;
	private SocketChannel socket;
	private Serializable lastReadObject;
	
	public SelectionKey getKey() {
		return key;
	}
	public void setKey(SelectionKey key) {
		this.key = key;
	}
	public SocketChannel getSocket() {
		return socket;
	}
	public void setSocket(SocketChannel socket) {
		this.socket = socket;
	}
	public Serializable getObject() {
		return lastReadObject;
	}
	public void setObject(Serializable object) {
		this.lastReadObject = object;
	}
}
