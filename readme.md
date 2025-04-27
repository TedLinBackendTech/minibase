cd /mnt/d/javaproject/asu/minibase/src
javac -d ../bin @sources.txt


cd bin
java batchcreate ../src/phase3_demo_data/data_1.txt table1
