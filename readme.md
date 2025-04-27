## What is this?

minibase

## how to build ?
# open wsl or linux/mac
cd /mnt/d/javaproject/asu/minibase/src
javac -d ../bin @sources.txt


cd bin
java batchcreate ../src/phase3_demo_data/data_1.txt table1


## Filter 查询
java query table1 table1 ../src/phase3_demo_data/fquery1.txt 100

## test NN without LSHF 
java query table1 table1 ../src/phase3_demo_data/nquery1.txt 100

## test NN without LSHF (change N to Y)
java query table1 table1 ../src/phase3_demo_data/nquery1.txt 100

## test DJoin without LSHF
java query table1 table2 ../src/phase3_demo_data/djquery_N.txt 100

## test DJoin with LSHF (fixing)
java query table1 table2 ../src/phase3_demo_data/djquery_N.txt 100

## todo 
LSHF issue fix