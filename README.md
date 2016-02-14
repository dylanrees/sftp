# sftp
SFTP (simple FTP) client-server combo written in Java for Internet Protocols class, Spring 2015.

SftpClient/SftpServer
by Dylan Rees, 5/5/2015

Description:

Transfers a specified file from the client to the server, then writes an output file (sftp_status_report.txt) that 
contains the file's name, filesize, and transfer time.  The server will then disconnect.  A client
will not connect to a server that is already connected to another client.

The server listens on port 9093 although the client may broadcast on any port.

The SftpClient utilizes a parameter N to specify the number of data packets it keeps in its buffer.  Setting this parameter
to N=1 within the client code will result in stop-and-wait behavior whereas N>1 yields Go-Back-N behavior.  In either
case, packets will be retransmitted upon timeout.

Both client and server will output supplementary information when run from a terminal.  In the case of the client this
includes a file transfer progress percentage.

Both client and server also have an optional loss rate parameter that causes them to randomly drop packets on the way
to the other program, simulating a congested network.  This parameter is given as a loss percentage from 0-100%.

Usage (client):

	java SftpClient <host> <port> <filename> <loss rate>

Usage (server):

	java SftpServer <loss rate>


KNOWN BUGS:
None are known at this time.


TESTING:
A 425kb image file was transferred between client and server and checked for integrity.
Generally speaking the results of the test suggest that transfer time is roughtly directly proportional
to packet losses and inversely proportional to the parameter N.

STOP AND WAIT (N=1)
0% losses: 0:14
10% losses: 4:34 (average)
20% losses: 9:36 (average)

GO BACK N:
N=2: 0:07
N=4: 0:04
N=8: 0:02
N=16: 0:01

GO BACK N (10% loss):
N=2: 2:13 (average)
N=4: 1:11 (average)
N=8: 0:39 (average)
N=16: 0:21 (average)
