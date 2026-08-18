# whiteboard

A shared whiteboard. Everyone draws on the same board and sees everyone
else's cursor, live. Each connection gets its own color.

Run it:

    bb dev                              # http://localhost:1390

or on the JVM:

    clojure -M -m buzz.whiteboard

`HOST` and `PORT` override the defaults for deployment.

The board is one SVG. Finished strokes, strokes in progress and other
cursors are three `server` slots, so cursor traffic patches nothing but the
cursor layer. Pointer moves go up as one `server!` call each; the message
counter in the top bar shows what a session costs.
