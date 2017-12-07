package io.amelia;

public interface ImplLogHandler
{
	void debug( Class<?> source, String message, Object... args );

	void fine( Class<?> source, String message, Object... args );

	void finest( Class<?> source, String message, Object... args );

	void info( String message, Object... args );

	void severe( Class<?> source, String message, Object... args );

	void severe( Class<?> source, String message, Throwable cause, Object... args );

	void warning( Class<?> source, String message, Throwable cause, Object... args );

	void warning( Class<?> source, String message, Object... args );
}
