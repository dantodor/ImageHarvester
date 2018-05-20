############################################################
# Dockerfile to build harvester slave
# Based on Ubuntu-java8
############################################################
FROM ubuntu:16.04
MAINTAINER Dan Todor
RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y  software-properties-common && \
    add-apt-repository ppa:webupd8team/java -y && \
    apt-get update && \
    echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections && \
    apt-get install -y oracle-java8-installer && \
    apt-get install -y imagemagick && \
    apt-get clean


COPY . /

RUN ldconfig /usr/local/lib

##################### INSTALLATION END #####################

# Expose the default port
EXPOSE 2551


# Set default container command
ENTRYPOINT ["/harvester/harvester-slave"]


