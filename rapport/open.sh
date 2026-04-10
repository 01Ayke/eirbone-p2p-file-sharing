#bin/bash

pdflatex main.tex
evince main.pdf &
rm *.aux *.log