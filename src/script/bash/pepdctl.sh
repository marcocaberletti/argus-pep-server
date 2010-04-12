#! /bin/bash
#
# Copyright (c) Members of the EGEE Collaboration. 2006-2010.
# See http://www.eu-egee.org/partners/ for details on the copyright holders.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

PEPD_HOME="$(cd "${0%/*}/.." && pwd)"
CONF="$PEPD_HOME/conf/pepd.ini"

# Source our environment setup script
. $PEPD_HOME/sbin/env.sh

# Add the PDP home directory property
JVMOPTS="-Dorg.glite.authz.pep.home=$PEPD_HOME $JVMOPTS"

function executeAdminCommand {
    HOST=`sed 's/ //g' $CONF | grep "^adminHost" | awk 'BEGIN {FS="="}{print $2}'`
    if [ -z $HOST ] ; then
       HOST="127.0.0.1"
    fi

    PORT=`sed 's/ //g' $CONF | grep "^adminPort" | awk 'BEGIN {FS="="}{print $2}'`
    if [ -z $PORT ] ; then
       PORT="8155"
    fi
    
    PASS=`sed 's/ //g' $CONF | grep "^adminPassword" | awk 'BEGIN {FS="="}{print $2}'`
    
    $JAVACMD $JVMOPTS 'org.glite.authz.common.http.JettyAdminServiceCLI' $HOST $PORT $1 $PASS
}

function start {
    # Run the PDP
    $JAVACMD $JVMOPTS 'org.glite.authz.pep.server.PEPDaemon' $CONF &
}


function print_help {
   echo "PEP Daemon control script"
   echo "Usage:"
   echo "  $0 start   - to start the service"
   echo "  $0 stop    - to stop the service" 
   echo "  $0 status  - print PEP daemon status"
   echo "  $0 clearResponseCache - clears the PEP daemon PDP response cache"
}

case "$1" in
    start)
        start
        ;;
    stop)
        executeAdminCommand 'shutdown' 
        ;;
    status) 
        executeAdminCommand 'status'
        ;;
    clearResponseCache) 
        executeAdminCommand 'clearResponseCache'
        ;;
    *) 
        print_help 
        exit 1
        ;;
esac
exit $? 

